package com.postcora.postclip.core

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

data class VideoMeta(
    val url: String,
    val title: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val uploader: String?,
)

/** Resolves a YouTube link with NewPipe Extractor. Blocking; call off the main thread. */
object VideoResolver {
    @Volatile private var initialised = false

    fun init(downloader: NewPipeDownloader = NewPipeDownloader()) {
        if (!initialised) synchronized(this) {
            if (!initialised) { NewPipe.init(downloader); initialised = true }
        }
    }

    fun normalise(input: String): String {
        val t = input.trim()
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return "https://www.youtube.com/watch?v=$t"
        return if (t.startsWith("http")) t else "https://$t"
    }

    fun resolve(input: String): StreamInfo {
        init()
        val url = normalise(input)
        val service = ServiceList.YouTube
        if (service.getStreamLHFactory().acceptUrl(url).not()) {
            throw UnsupportedVideoException("That doesn't look like a YouTube video link.")
        }
        val info = StreamInfo.getInfo(service, url)
        StreamSelector.checkSupported(info)
        return info
    }

    fun meta(info: StreamInfo): VideoMeta = VideoMeta(
        url = info.url,
        title = info.name,
        durationSeconds = info.duration,
        thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
        uploader = info.uploaderName,
    )
}
