package com.cleantube.app

import android.app.Application
import com.cleantube.app.data.BiliAuth
import com.cleantube.app.data.DownloadEngine
import com.cleantube.app.data.HistoryStore
import com.cleantube.app.data.YouTubeParser

class CleanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        HistoryStore.init(this)
        YouTubeParser.ensureInit()
        BiliAuth.cookieHeader(this)?.let { com.cleantube.app.data.BiliParser.cookieHeader = it }
    }
}
