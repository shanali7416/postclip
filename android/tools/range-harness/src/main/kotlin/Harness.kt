import com.postcora.postclip.core.*

/** Resolves a video with NewPipe Extractor and prints the exact streams the app would use, as JSON. */
fun main(args: Array<String>) {
    val id = args[0]
    val maxHeight = args.getOrNull(1)?.toInt() ?: 720
    VideoResolver.init()
    val info = VideoResolver.resolve(id)
    val meta = VideoResolver.meta(info)
    val plan = StreamSelector.plan(info, maxHeight)
    fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun p(m: MediaPick?) = if (m == null) "null" else
        "{\"url\":${q(m.url)},\"width\":${m.width},\"height\":${m.height},\"bitrate\":${m.bitrate},\"contentLength\":${m.contentLength},\"approxDurationMs\":${m.approxDurationMs},\"label\":${q(m.label)},\"codec\":${q(m.codec)}}"
    println("{\"title\":${q(meta.title)},\"duration\":${meta.durationSeconds},\"streamType\":${q(info.streamType.name)}," +
        "\"heights\":${StreamSelector.availableHeights(info)},\"progressive\":${plan.progressive}," +
        "\"video\":${p(plan.video)},\"audio\":${p(plan.audio)},\"fullBytes\":${plan.fullBytes}}")
}
