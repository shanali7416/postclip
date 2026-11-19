package com.postcora.postclip

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.postcora.postclip.core.VideoResolver
import com.postcora.postclip.download.ClipDownloadService

@UnstableApi
class PostClipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VideoResolver.init()
        ClipDownloadService.createChannel(this)
    }
}
