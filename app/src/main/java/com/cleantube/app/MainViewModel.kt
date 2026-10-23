package com.cleantube.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cleantube.app.data.BiliAuth
import com.cleantube.app.data.BiliParser
import com.cleantube.app.data.DownloadEngine
import com.cleantube.app.data.HistoryStore
import com.cleantube.app.data.Platform
import com.cleantube.app.data.StreamOption
import com.cleantube.app.data.VideoResult
import com.cleantube.app.data.YouTubeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    enum class Screen { HOME, HISTORY }

    sealed interface BiliLoginUi {
        data object Checking : BiliLoginUi
        data class LoggedIn(val uname: String) : BiliLoginUi
        data class Qr(val content: String, val status: String, val expired: Boolean) : BiliLoginUi
        data class Error(val message: String) : BiliLoginUi
    }

    val downloads = DownloadEngine.tasks
    val history = HistoryStore.items

    var link by mutableStateOf("")
        private set
    var parsing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<VideoResult?>(null)
        private set

    var screen by mutableStateOf(Screen.HOME)
        private set
    var showBiliLogin by mutableStateOf(false)
        private set
    var biliLogin by mutableStateOf<BiliLoginUi>(BiliLoginUi.Checking)
        private set
    var biliLoggedIn by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    init {
        biliLoggedIn = BiliAuth.isLoggedIn(app)
    }

    val biliHint: Boolean
        get() = result?.platform == Platform.BILIBILI && !biliLoggedIn

    fun onLinkChange(value: String) {
        link = value
        error = null
    }

    /** 从分享/打开方式进入时调用：提取文本中的链接并自动解析 */
    fun submitExternalLink(text: String) {
        val url = Regex("https?://[^\\s]+").find(text)?.value
            ?.trimEnd { it.code > 0x7F }
            ?: return
        link = url
        parse()
    }

    private fun extract(url: String): VideoResult = when {
        url.contains("bilibili.com") || url.contains("b23.tv") -> BiliParser.parse(url)
        url.contains("youtube.com") || url.contains("youtu.be") -> YouTubeParser.parse(url)
        else -> throw IOException("暂不支持该链接，目前支持 YouTube 和哔哩哔哩")
    }

    fun parse() {
        val url = link.trim()
        if (url.isEmpty()) {
            error = "请先粘贴视频链接"
            return
        }
        if (parsing) return
        parsing = true
        error = null
        result = null
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { extract(url) }
                result = r
            } catch (e: Exception) {
                error = e.message ?: "解析失败，请检查链接或稍后重试"
            } finally {
                parsing = false
            }
        }
    }

    fun download(option: StreamOption) {
        val r = result ?: return
        DownloadEngine.start(r, option)
    }

    fun removeTask(key: String) = DownloadEngine.remove(key)

    fun dismissError() {
        error = null
    }

    fun switchScreen() {
        screen = if (screen == Screen.HOME) Screen.HISTORY else Screen.HOME
    }

    fun removeHistory(key: String) = HistoryStore.remove(key)

    fun clearHistory() = HistoryStore.clear()

    fun openBiliLogin() {
        showBiliLogin = true
        refreshBiliLogin()
    }

    fun closeBiliLogin() {
        showBiliLogin = false
        pollJob?.cancel()
    }

    fun logoutBili() {
        BiliAuth.clear(app)
        BiliParser.cookieHeader = null
        biliLoggedIn = false
        refreshBiliLogin()
    }

    fun refreshBiliLogin() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            biliLogin = BiliLoginUi.Checking
            if (BiliAuth.isLoggedIn(app)) {
                val uname = withContext(Dispatchers.IO) {
                    runCatching { BiliAuth.fetchUname(app) }.getOrNull()
                }
                biliLoggedIn = true
                biliLogin = BiliLoginUi.LoggedIn(uname ?: "已登录")
            } else {
                startQrFlow()
            }
        }
    }

    private suspend fun startQrFlow() {
        val session = try {
            withContext(Dispatchers.IO) { BiliAuth.createSession() }
        } catch (e: Exception) {
            biliLogin = BiliLoginUi.Error(e.message ?: "获取二维码失败，请重试")
            return
        }
        biliLogin = BiliLoginUi.Qr(session.url, "请用哔哩哔哩 App 扫码", false)
        while (true) {
            delay(2000)
            val r = try {
                withContext(Dispatchers.IO) { BiliAuth.poll(session.key) }
            } catch (e: Exception) {
                BiliAuth.PollResult.Failed(e.message ?: "网络错误")
            }
            when (r) {
                BiliAuth.PollResult.Waiting ->
                    biliLogin = BiliLoginUi.Qr(session.url, "等待扫码…", false)
                BiliAuth.PollResult.Scanned ->
                    biliLogin = BiliLoginUi.Qr(session.url, "已扫码，请在手机上确认", false)
                is BiliAuth.PollResult.Failed ->
                    biliLogin = BiliLoginUi.Qr(session.url, r.message, expired = true)
                is BiliAuth.PollResult.Success -> {
                    BiliAuth.saveCookies(app, r.setCookies)
                    BiliParser.cookieHeader = BiliAuth.cookieHeader(app)
                    biliLoggedIn = true
                    val uname = withContext(Dispatchers.IO) {
                        runCatching { BiliAuth.fetchUname(app) }.getOrNull()
                    }
                    biliLogin = BiliLoginUi.LoggedIn(uname ?: "登录成功")
                    return
                }
            }
        }
    }
}
