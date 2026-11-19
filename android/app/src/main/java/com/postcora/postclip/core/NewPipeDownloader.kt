package com.postcora.postclip.core

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** OkHttp-backed downloader for NewPipe Extractor. No login, no API key. */
class NewPipeDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = data?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(method, if (method == "POST" || method == "PUT") (body ?: ByteArray(0).toRequestBody()) else null)
            .url(request.url())
            .addHeader("User-Agent", CHROME_UA)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                resp.close()
                throw ReCaptchaException("reCaptcha / rate limit (HTTP 429)", request.url())
            }
            val bodyString = resp.body?.string()
            return Response(resp.code, resp.message, resp.headers.toMultimap(), bodyString, resp.request.url.toString())
        }
    }
}
