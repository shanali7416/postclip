package com.postcora.postclip.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Splits one open-ended read of a googlevideo URL into bounded HTTP Range requests (default 4 MB).
 * YouTube servers throttle or drop long open-ended responses, which is what made long clips fail;
 * bounded ranges keep every request short. Only the bytes of the requested range are ever fetched.
 *
 * [totalLength] is the stream's content length from NewPipe (0 / unknown -> pass-through).
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val totalLength: (Uri) -> Long,
    private val chunkSize: Long = 4L * 1024 * 1024,
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkEnd = 0L
    private var chunkOpen = false
    private var passThrough = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val total = totalLength(dataSpec.uri)
        if (total <= 0) {
            passThrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }
        passThrough = false
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else total
        if (position >= end) return 0
        openChunk()
        return end - position
    }

    private fun openChunk() {
        val base = spec!!
        chunkEnd = minOf(end, position + chunkSize)
        upstream.open(base.buildUpon().setPosition(position).setLength(chunkEnd - position).build())
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passThrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        var emptyReopens = 0
        while (true) {
            if (position >= end) return C.RESULT_END_OF_INPUT
            if (!chunkOpen) openChunk()
            val toRead = minOf(length.toLong(), chunkEnd - position).toInt()
            val n = upstream.read(buffer, offset, toRead)
            if (n > 0) {
                position += n
                if (position >= chunkEnd) { upstream.close(); chunkOpen = false }
                return n
            }
            // End of this chunk (or the server closed early): open the next range from `position`.
            upstream.close()
            chunkOpen = false
            if (position < chunkEnd && ++emptyReopens > 3) {
                throw java.io.IOException("Server kept closing the connection at byte $position")
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (chunkOpen) upstream.close()
        chunkOpen = false
        spec = null
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val totalLength: (Uri) -> Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstreamFactory.createDataSource(), totalLength)
    }
}
