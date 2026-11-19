package com.postcora.postclip

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.postcora.postclip.core.ClipValidation
import com.postcora.postclip.core.SizeEstimate
import com.postcora.postclip.core.StreamSelector
import com.postcora.postclip.core.TimeFormat
import com.postcora.postclip.core.TimeParse
import com.postcora.postclip.core.UnsupportedVideoException
import com.postcora.postclip.core.VideoMeta
import com.postcora.postclip.core.VideoResolver
import com.postcora.postclip.download.ClipDownloadService
import com.postcora.postclip.download.ClipJob
import com.postcora.postclip.download.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo

data class UiState(
    val link: String = "",
    val loading: Boolean = false,
    val loadError: String? = null,
    val meta: VideoMeta? = null,
    val heights: List<Int> = emptyList(),
    val selectedHeight: Int = StreamSelector.DEFAULT_MAX_HEIGHT,
    val startText: String = "0:00",
    val endText: String = "0:30",
    val crop916: Boolean = false,
    val rangeError: String? = null,
    val estimateBytes: Long = 0,
    val usesDash: Boolean = true,
    val preparing: Boolean = false,
)

@UnstableApi
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    val download = DownloadRepository.state

    private var info: StreamInfo? = null
    private var resolvedAt = 0L

    fun setLink(s: String) { _ui.value = _ui.value.copy(link = s) }

    fun fetch() {
        val link = _ui.value.link.trim()
        if (link.isEmpty()) return
        _ui.value = _ui.value.copy(loading = true, loadError = null)
        viewModelScope.launch {
            try {
                val i = withContext(Dispatchers.IO) { VideoResolver.resolve(link) }
                info = i; resolvedAt = SystemClock.elapsedRealtime()
                val heights = StreamSelector.availableHeights(i)
                val sel = heights.firstOrNull { it <= StreamSelector.DEFAULT_MAX_HEIGHT } ?: heights.lastOrNull() ?: 720
                val dur = i.duration
                DownloadRepository.reset()
                _ui.value = _ui.value.copy(
                    loading = false, meta = VideoResolver.meta(i), heights = heights, selectedHeight = sel,
                    startText = "0:00", endText = TimeFormat.format(minOf(30L, dur.coerceAtLeast(1))),
                )
                recompute()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, loadError = friendly(e))
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnsupportedVideoException -> e.message ?: "Unsupported video."
        is ReCaptchaException -> "YouTube is asking for a bot check from this network. Try again later or on another network."
        is ContentNotAvailableException -> "This video is not available (private, removed, age- or region-restricted)."
        is java.io.IOException -> "Network error: ${e.message ?: "check your connection"}"
        else -> "Could not read this video: ${e.message ?: e.javaClass.simpleName}"
    }

    fun setStart(s: String) { _ui.value = _ui.value.copy(startText = s); recompute() }
    fun setEnd(s: String) { _ui.value = _ui.value.copy(endText = s); recompute() }
    fun setCrop(b: Boolean) { _ui.value = _ui.value.copy(crop916 = b) }
    fun setHeight(h: Int) { _ui.value = _ui.value.copy(selectedHeight = h); recompute() }

    fun applyPreset(seconds: Long) {
        val start = TimeFormat.parseSeconds(_ui.value.startText) ?: 0L
        _ui.value = _ui.value.copy(endText = TimeFormat.format(ClipValidation.presetEnd(start, seconds)))
        recompute()
    }

    private fun recompute() {
        val u = _ui.value
        val i = info ?: return
        val s = TimeFormat.parse(u.startText)
        val e = TimeFormat.parse(u.endText)
        val err = when {
            s is TimeParse.Error -> "Start: ${s.message}"
            e is TimeParse.Error -> "End: ${e.message}"
            else -> ClipValidation.validate((s as TimeParse.Ok).seconds, (e as TimeParse.Ok).seconds, i.duration)
        }
        var est = 0L
        var dash = true
        try {
            val plan = StreamSelector.plan(i, u.selectedHeight)
            dash = !plan.progressive
            if (err == null) {
                val clip = (e as TimeParse.Ok).seconds - (s as TimeParse.Ok).seconds
                est = SizeEstimate.clipBytes(plan.fullBytes, i.duration, clip, plan.totalBitrate)
            }
        } catch (_: Exception) { }
        _ui.value = u.copy(rangeError = err, estimateBytes = est, usesDash = dash)
    }

    fun startDownload() {
        val u = _ui.value
        if (u.rangeError != null || info == null || _ui.value.preparing) return
        val start = TimeFormat.parseSeconds(u.startText) ?: return
        val end = TimeFormat.parseSeconds(u.endText) ?: return
        _ui.value = u.copy(preparing = true)
        viewModelScope.launch {
            try {
                // Stream URLs expire after a few hours: re-resolve if the info is older than 30 minutes
                // (also used by "Try again" after a failure).
                val stale = SystemClock.elapsedRealtime() - resolvedAt > 30 * 60 * 1000 ||
                    download.value is com.postcora.postclip.download.DownloadState.Failed
                if (stale) {
                    info = withContext(Dispatchers.IO) { VideoResolver.resolve(info!!.url) }
                    resolvedAt = SystemClock.elapsedRealtime()
                }
                val i = info!!
                val plan = StreamSelector.plan(i, u.selectedHeight)
                val est = SizeEstimate.clipBytes(plan.fullBytes, i.duration, end - start, plan.totalBitrate)
                val job = ClipJob(i.id, i.name, plan, start, end, u.crop916, est)
                ClipDownloadService.start(getApplication(), job)
            } catch (e: Exception) {
                DownloadRepository.update(com.postcora.postclip.download.DownloadState.Failed(friendly(e)))
            } finally {
                _ui.value = _ui.value.copy(preparing = false)
            }
        }
    }

    fun cancel() = ClipDownloadService.cancel(getApplication())
    fun dismissResult() = DownloadRepository.reset()
}
