package com.postcora.postclip.core

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** A downloadable media URL with what we know about it. */
data class MediaPick(
    val url: String,
    val width: Int,
    val height: Int,
    val bitrate: Long,          // bits per second
    val contentLength: Long,    // bytes, 0 if unknown
    val approxDurationMs: Long, // 0 if unknown
    val label: String,
    val codec: String?,
)

/** What the app downloads for one quality choice. */
data class ClipPlan(
    val video: MediaPick,
    val audio: MediaPick?,      // null for progressive (muxed) or when no audio exists
    val progressive: Boolean,
) {
    val fullBytes: Long get() = video.contentLength + (audio?.contentLength ?: 0)
    val totalBitrate: Long get() = video.bitrate + (audio?.bitrate ?: 0)
}

class UnsupportedVideoException(message: String) : Exception(message)

object StreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    fun isEndedVod(type: StreamType): Boolean =
        type == StreamType.VIDEO_STREAM || type == StreamType.POST_LIVE_STREAM

    fun checkSupported(info: StreamInfo) {
        when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM ->
                throw UnsupportedVideoException("Live streams are not supported. Try again after the stream has ended.")
            else -> if (!isEndedVod(info.streamType))
                throw UnsupportedVideoException("This link is not a regular video.")
        }
    }

    private fun usable(s: org.schabi.newpipe.extractor.stream.Stream) =
        s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.content.startsWith("http")

    private fun heightOf(v: VideoStream): Int =
        if (v.height > 0) v.height else v.getResolution().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun pickOf(v: VideoStream): MediaPick {
        val itag = v.itagItem
        return MediaPick(
            url = v.content,
            width = v.width,
            height = heightOf(v),
            bitrate = v.bitrate.toLong().coerceAtLeast(0),
            contentLength = itag?.contentLength ?: 0,
            approxDurationMs = itag?.approxDurationMs ?: 0,
            label = "${heightOf(v)}p" + (if (v.fps > 30) "${v.fps}" else ""),
            codec = v.codec,
        )
    }

    private fun pickOf(a: AudioStream): MediaPick {
        val itag = a.itagItem
        val br = if (a.bitrate > 0) a.bitrate.toLong() else a.averageBitrate.toLong() * 1000
        return MediaPick(a.content, 0, 0, br, itag?.contentLength ?: 0, itag?.approxDurationMs ?: 0,
            "${a.averageBitrate} kbps", a.codec)
    }

    /** MP4 video-only streams (H.264 preferred), usable over HTTP, sorted by height desc. */
    fun mp4VideoOnly(info: StreamInfo): List<VideoStream> =
        info.videoOnlyStreams
            .filter { it.isVideoOnly && it.format == MediaFormat.MPEG_4 && usable(it) }
            .sortedWith(compareByDescending<VideoStream> { heightOf(it) }
                .thenByDescending { it.codec?.startsWith("avc") == true }
                .thenByDescending { it.bitrate })

    /** Distinct heights the quality picker offers (<= 1080). */
    fun availableHeights(info: StreamInfo): List<Int> {
        val dash = mp4VideoOnly(info).map { heightOf(it) }.filter { it in 1..1080 }
        val prog = info.videoStreams.filter { !it.isVideoOnly && usable(it) }.map { heightOf(it) }
        return (dash + prog).distinct().sortedDescending()
    }

    /** Best m4a audio-only stream, preferring the original audio track. */
    fun bestAudio(info: StreamInfo): AudioStream? =
        info.audioStreams
            .filter { it.format == MediaFormat.M4A && usable(it) }
            .sortedWith(compareByDescending<AudioStream> {
                it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
            }.thenByDescending { it.averageBitrate })
            .firstOrNull()

    /**
     * Same choice the app makes: best MP4 video-only <= maxHeight + best m4a audio.
     * A progressive (muxed) stream is used only when one is present and no video-only/audio pair is.
     */
    fun plan(info: StreamInfo, maxHeight: Int = DEFAULT_MAX_HEIGHT): ClipPlan {
        val video = mp4VideoOnly(info).firstOrNull { heightOf(it) in 1..maxHeight }
        val audio = bestAudio(info)
        if (video != null && audio != null) return ClipPlan(pickOf(video), pickOf(audio), progressive = false)
        val progressive = info.videoStreams
            .filter { !it.isVideoOnly && usable(it) && heightOf(it) <= maxHeight }
            .maxByOrNull { heightOf(it) }
        if (progressive != null) return ClipPlan(pickOf(progressive), null, progressive = true)
        if (video != null) return ClipPlan(pickOf(video), null, progressive = false)
        throw UnsupportedVideoException("No downloadable MP4 stream found for this video.")
    }
}
