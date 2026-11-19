package com.postcora.postclip.download

import android.net.Uri
import com.postcora.postclip.core.ClipPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything one clip download needs. Kept in memory and handed to the service. */
data class ClipJob(
    val videoId: String,
    val title: String,
    val plan: ClipPlan,
    val startSec: Long,
    val endSec: Long,
    val crop916: Boolean,
    val estimatedBytes: Long,
    /** true = "Download full video" (whole length, no clipping, no crop); false = clip range only. */
    val fullVideo: Boolean = false,
) {
    val clipSec: Long get() = endSec - startSec
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Running(
        val percent: Int,
        val bytesDownloaded: Long,
        val bytesTotalEstimate: Long,
        val speedBps: Double,
        val etaSeconds: Long?,
        val attempt: Int,
        val stage: String,
    ) : DownloadState()
    data class Done(val uri: Uri, val displayPath: String, val sizeBytes: Long, val durationMs: Long) : DownloadState()
    data class Failed(val message: String) : DownloadState()
    data object Cancelled : DownloadState()
}

/** Process-wide state shared by the UI and [ClipDownloadService]. */
object DownloadRepository {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    @Volatile var pendingJob: ClipJob? = null

    fun update(s: DownloadState) { _state.value = s }
    fun reset() { if (_state.value !is DownloadState.Running) _state.value = DownloadState.Idle }
}
