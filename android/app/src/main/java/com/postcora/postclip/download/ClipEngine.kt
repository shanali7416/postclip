package com.postcora.postclip.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.postcora.postclip.core.CHROME_UA
import java.io.File
import java.io.IOException

/**
 * Cuts [ClipJob]'s range straight from the remote YouTube streams with Media3 Transformer.
 * Only the clip range is fetched (HTTP Range requests); the full video is never downloaded.
 *
 * Attempt 1: two-sequence Composition (video-only MP4 + m4a audio), identical ClippingConfiguration
 *            for both (audio end clamped to the audio length), audio re-encoded to AAC, video to H.264,
 *            Media3 InAppMuxer, no muxer watchdog timeout.
 * Attempt 2 (retry after a failure): both streams merged into ONE media item (MergingMediaSource of two
 *            individually clipped sources), forced full transcode, InAppMuxer.
 * Must be used from the main thread.
 */
@UnstableApi
class ClipEngine(
    private val context: Context,
    private val job: ClipJob,
    private val output: File,
    private val onProgress: (DownloadState.Running) -> Unit,
    private val onFinished: (Result<ExportResult>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    @Volatile private var bytes = 0L
    private var startedAt = 0L
    private var attempt = 0
    private var lastBytes = 0L
    private var lastPercent = -1
    private var lastChangeAt = 0L
    private var speed = 0.0
    private var speedSampleBytes = 0L
    private var speedSampleAt = 0L
    private var cancelled = false

    private val lengths: Map<String, Long> = buildMap {
        put(job.plan.video.url, job.plan.video.contentLength)
        job.plan.audio?.let { put(it.url, it.contentLength) }
    }

    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytes += bytesTransferred
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    private fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(CHROME_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setTransferListener(transferListener)
        return ChunkedDataSource.Factory(http) { uri -> lengths[uri.toString()] ?: 0L }
    }

    /** Retries failed segment loads with backoff instead of failing the clip. */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 12) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val code = (info.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code == 403 || code == 404 || code == 410) return C.TIME_UNSET // link expired: Try again re-resolves
            return minOf(1_000L * info.errorCount, 8_000L)
        }
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12
    }

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        lastChangeAt = startedAt
        speedSampleAt = startedAt
        run(1)
        main.post(progressTicker)
    }

    fun cancel() {
        cancelled = true
        main.removeCallbacks(progressTicker)
        transformer?.cancel()
    }

    private fun clipping(endMs: Long) = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(job.startSec * 1000)
        .setEndPositionMs(endMs)
        .build()

    private fun audioEndMs(): Long {
        val clipEnd = job.endSec * 1000
        val audioDur = job.plan.audio?.approxDurationMs ?: 0
        return if (audioDur > 0) minOf(clipEnd, audioDur) else clipEnd
    }

    /** Whether the audio stream covers any of the clip (else the clip is video-only, no crash). */
    private fun audioUsable(): Boolean = job.plan.audio != null && audioEndMs() > job.startSec * 1000 + 100

    private fun videoEffects(forceTranscode: Boolean): List<androidx.media3.common.Effect> = when {
        job.crop916 -> listOf(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        forceTranscode && job.plan.video.height > 0 -> listOf(Presentation.createForHeight(job.plan.video.height))
        else -> emptyList()
    }

    /** A no-op processor in the chain forces Transformer to decode and re-encode audio to AAC. */
    private fun audioProcessors(): List<AudioProcessor> = listOf(SonicAudioProcessor())

    private fun buildTransformer(mediaSourceFactory: MediaSource.Factory): Transformer {
        val assetLoaderFactory = DefaultAssetLoaderFactory(
            context, DefaultDecoderFactory(context), Clock.DEFAULT, mediaSourceFactory, DataSourceBitmapLoader(context),
        )
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
            .setMuxerFactory(InAppMuxer.Factory.Builder().build())
            // A slow mobile link can stall one input for >10 s; Media3's default watchdog then aborts with
            // "Muxer error". We disable it and use our own stall detector that looks at network bytes too.
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
            .setAssetLoaderFactory(assetLoaderFactory)
            .addListener(listener)
            .build()
    }

    private fun run(n: Int) {
        attempt = n
        startedAt = SystemClock.elapsedRealtime()
        lastChangeAt = startedAt
        output.delete()
        val t = if (n == 1) startTwoSequence() else startMerged()
        transformer = t
    }

    private fun startTwoSequence(): Transformer {
        val msf = DefaultMediaSourceFactory(dataSourceFactory()).setLoadErrorHandlingPolicy(loadErrorPolicy)
        val t = buildTransformer(msf)
        val videoItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(job.plan.video.url).setClippingConfiguration(clipping(job.endSec * 1000)).build()
        ).setRemoveAudio(job.plan.audio != null && !job.plan.progressive)
            .setEffects(Effects(if (job.plan.progressive) audioProcessors() else emptyList(), videoEffects(false)))
            .build()
        val sequences = mutableListOf(EditedMediaItemSequence(videoItem))
        val audio = job.plan.audio
        if (audio != null && !job.plan.progressive && audioUsable()) {
            val audioItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(audio.url).setClippingConfiguration(clipping(audioEndMs())).build()
            ).setRemoveVideo(true).setEffects(Effects(audioProcessors(), emptyList())).build()
            sequences += EditedMediaItemSequence(audioItem)
        }
        t.start(Composition.Builder(sequences).build(), output.absolutePath)
        return t
    }

    /** Retry path: one media item whose source merges the two individually clipped remote streams. */
    private fun startMerged(): Transformer {
        val dsf = dataSourceFactory()
        val startUs = job.startSec * 1_000_000
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                fun prog(url: String) = ProgressiveMediaSource.Factory(dsf)
                    .setLoadErrorHandlingPolicy(loadErrorPolicy)
                    .createMediaSource(MediaItem.fromUri(url))
                val video = ClippingMediaSource(prog(job.plan.video.url), startUs, job.endSec * 1_000_000)
                val audio = job.plan.audio
                if (audio == null || job.plan.progressive || !audioUsable()) return video
                val audioSrc = ClippingMediaSource(prog(audio.url), startUs, audioEndMs() * 1000)
                return MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, /* clipDurations= */ false, video, audioSrc)
            }
        }
        val t = buildTransformer(factory)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(job.plan.video.url))
            .setRemoveAudio(job.plan.audio == null && !job.plan.progressive)
            .setEffects(Effects(audioProcessors(), videoEffects(true)))
            .build()
        t.start(item, output.absolutePath)
        return t
    }

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            main.removeCallbacks(progressTicker)
            onFinished(Result.success(exportResult))
        }

        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            handleFailure(describe(exportException), exportException)
        }
    }

    private fun handleFailure(message: String, cause: Throwable?) {
        if (cancelled) return
        if (attempt == 1) {
            // Retry once with the full-transcode merged pipeline. Never falls back to a full download.
            transformer = null
            main.postDelayed({ if (!cancelled) run(2) }, 1_500)
            return
        }
        main.removeCallbacks(progressTicker)
        onFinished(Result.failure(IOException(message, cause)))
    }

    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Network connection lost while fetching the clip."
        ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "YouTube refused the request (the link may have expired)."
        ExportException.ERROR_CODE_MUXING_FAILED, ExportException.ERROR_CODE_MUXING_TIMEOUT ->
            "Could not write the MP4 (muxer error) even after a full re-encode."
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_DECODER_INIT_FAILED ->
            "This phone's video codec could not process this quality. Try a lower quality."
        else -> "Clip failed: ${e.errorCodeName}"
    } + " Only the selected range is fetched; tap Try again."

    /** Ticks every 500 ms: progress (based on clip duration), MB, speed, ETA, and a stall detector. */
    private val progressTicker = object : Runnable {
        override fun run() {
            val t = transformer
            val now = SystemClock.elapsedRealtime()
            var percent = lastPercent.coerceAtLeast(0)
            if (t != null) {
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) percent = holder.progress
            }
            val b = bytes
            if (b != lastBytes || percent != lastPercent) lastChangeAt = now
            lastBytes = b; lastPercent = percent
            if (now - speedSampleAt >= 2_000) {
                val inst = (b - speedSampleBytes) * 1000.0 / (now - speedSampleAt)
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                speedSampleBytes = b; speedSampleAt = now
            }
            val elapsed = (now - startedAt) / 1000.0
            val eta = if (percent in 1..99) ((elapsed * (100 - percent)) / percent).toLong() else null
            onProgress(DownloadState.Running(percent, b, job.estimatedBytes, speed, eta, attempt,
                if (attempt == 1) "Fetching clip range" else "Retrying with full re-encode"))
            // Neither network bytes nor progress for 3 minutes: give up on this attempt.
            if (t != null && now - lastChangeAt > 180_000) {
                lastChangeAt = now
                t.cancel()
                transformer = null
                main.post { handleFailure("The download stalled (no data for 3 minutes). Only the selected range is fetched; tap Try again.", null) }
                if (attempt == 1) main.postDelayed(this, 2_000)
                return
            }
            main.postDelayed(this, 500)
        }
    }
}
