package com.cleantube.app.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 哔哩哔哩扫码登录：
 * 1. qrcode/generate 拿二维码内容和 qrcode_key
 * 2. 轮询 qrcode/poll 直到用户扫码确认，成功后从 Set-Cookie 里取 SESSDATA 等
 * 3. Cookie 持久化到 SharedPreferences（纯本地）
 */
object BiliAuth {

    private const val PREF = "bili_login"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val COOKIE_NAMES = listOf("DedeUserID", "SESSDATA", "bili_jct", "DedeUserID__ckMd5", "sid")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class QrSession(val url: String, val key: String)

    sealed class PollResult {
        object Waiting : PollResult()
        object Scanned : PollResult()
        data class Success(val setCookies: List<String>) : PollResult()
        data class Failed(val message: String) : PollResult()
    }

    fun cookieHeader(context: Context): String? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val sess = sp.getString("SESSDATA", null) ?: return null
        return buildList {
            sp.getString("DedeUserID", null)?.let { add("DedeUserID=$it") }
            add("SESSDATA=$sess")
            sp.getString("bili_jct", null)?.let { add("bili_jct=$it") }
        }.joinToString("; ")
    }

    fun isLoggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("SESSDATA", null) != null

    fun saveCookies(context: Context, setCookies: List<String>) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        val combined = setCookies.joinToString("; ")
        for (name in COOKIE_NAMES) {
            Regex("""(?:^|[;\s])${Regex.escape(name)}=([^;\s]+)""").find(combined)?.let { m ->
                sp.putString(name, m.groupValues[1])
            }
        }
        sp.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun createSession(): QrSession {
        val req = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("二维码接口返回为空")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw IOException("获取登录二维码失败：${json.optString("message")}")
            }
            val data = json.getJSONObject("data")
            return QrSession(data.getString("url"), data.getString("qrcode_key"))
        }
    }

    fun poll(key: String): PollResult {
        val req = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return PollResult.Failed("轮询返回为空")
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return PollResult.Failed("轮询返回异常")
            return when (data.optInt("code", -1)) {
                86101 -> PollResult.Waiting
                86090 -> PollResult.Scanned
                86038 -> PollResult.Failed("二维码已过期，请刷新")
                0 -> PollResult.Success(resp.headers("Set-Cookie"))
                else -> PollResult.Failed("扫码状态未知：${data.optInt("code")}")
            }
        }
    }

    /** 登录后查询用户名（未登录返回 null） */
    fun fetchUname(context: Context): String? {
        val cookie = cookieHeader(context) ?: return null
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/nav")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com")
            .header("Cookie", cookie)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            val data = JSONObject(body).optJSONObject("data") ?: return null
            return if (data.optBoolean("isLogin", false)) data.optString("uname") else null
        }
    }
}
