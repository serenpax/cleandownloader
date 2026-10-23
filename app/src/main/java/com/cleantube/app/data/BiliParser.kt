package com.cleantube.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * 哔哩哔哩解析：b23.tv 短链展开 → view API 拿元数据 → wbi 签名的 playurl API 拿 DASH 分离流。
 * DASH 结构与 YouTube 高清流一致，复用 DownloadEngine 的合并逻辑。
 */
object BiliParser {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.bilibili.com"

    /** wbi mixin 置换表（社区公开的逆向成果，长期稳定） */
    private val MIXIN = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 47, 1,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedWbiKeys: Pair<String, String>? = null

    /** 登录 Cookie（SESSDATA 等），由 BiliAuth 管理，登录成功后注入 */
    @Volatile
    var cookieHeader: String? = null

    fun parse(rawUrl: String): VideoResult {
        val expanded = expandUrl(rawUrl.trim())
        val bvid = Regex("BV[a-zA-Z0-9]{10}").find(expanded)?.value
            ?: throw IOException("无法从链接中识别视频号（BV号）")
        val pageNo = Regex("[?&]p=(\\d+)").find(expanded)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val view = apiGet("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        val pages = view.getJSONArray("pages")
        val pageIndex = (pageNo - 1).coerceIn(0, pages.length() - 1)
        val page = pages.getJSONObject(pageIndex)
        val cid = page.getLong("cid")

        val query = TreeMap<String, String>().apply {
            put("bvid", bvid)
            put("cid", cid.toString())
            put("fnval", "16")
            put("fourk", "1")
        }
        val play = apiGet("https://api.bilibili.com/x/player/wbi/playurl?${signWbi(query)}")
        val dash = play.optJSONObject("dash")
            ?: throw IOException("没有拿到 DASH 流信息（直播或受限视频不支持）")

        val companion = pickBestAudio(dash)
        val videoOptions = collectVideoOptions(dash, companion)
        val audioOptions = collectAudioOptions(dash)
        check(videoOptions.isNotEmpty() || audioOptions.isNotEmpty()) {
            "没有拿到可下载的流（可能需要登录或视频受限）"
        }

        val partName = page.optString("part")
        val multiPage = pages.length() > 1
        val title = buildString {
            append(view.optString("title"))
            if (multiPage) append(" - P").append(pageIndex + 1).append(" ").append(partName)
        }

        return VideoResult(
            id = bvid + "_p" + (pageIndex + 1),
            platform = Platform.BILIBILI,
            title = title,
            uploader = view.optJSONObject("owner")?.optString("name") ?: "",
            durationSec = page.optLong("duration", view.optLong("duration", 0)),
            thumbnailUrl = view.optString("pic").let { if (it.startsWith("//")) "https:$it" else it },
            options = videoOptions + audioOptions,
        )
    }

    private fun collectVideoOptions(
        dash: JSONObject,
        companion: Triple<String, String, List<String>>?,
    ): List<StreamOption> {
        val arr = dash.optJSONArray("video") ?: return emptyList()
        // 同一清晰度可能有 avc1/hev1/av01 多个编码，优先 avc1（兼容性最好）
        val byQuality = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            if (v.optBoolean("disabled", false)) continue
            val q = v.getInt("id")
            val existing = byQuality[q]
            if (existing == null || (isAvc(v) && !isAvc(existing))) byQuality[q] = v
        }
        return byQuality.entries
            .sortedByDescending { it.key }
            .mapNotNull { (q, v) ->
                val url = streamUrl(v) ?: return@mapNotNull null
                StreamOption(
                    itag = "bili$q",
                    kind = StreamOption.Kind.VIDEO_ONLY,
                    resolution = qualityLabel(q),
                    fps = v.optInt("frameRate", 0).takeIf { it > 0 },
                    format = "MP4",
                    bitrateKbps = null,
                    url = url,
                    companionAudioUrl = companion?.first,
                    companionFormat = companion?.second,
                    referer = REFERER,
                    backupUrls = streamBackups(v),
                    backupAudioUrls = companion?.third.orEmpty(),
                    cookie = cookieHeader,
                )
            }
    }

    private fun collectAudioOptions(dash: JSONObject): List<StreamOption> {
        val arr = dash.optJSONArray("audio") ?: return emptyList()
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getInt("id") in AUDIO_BITRATE }
            .mapNotNull { a ->
                val url = streamUrl(a) ?: return@mapNotNull null
                StreamOption(
                    itag = "biliao${a.getInt("id")}",
                    kind = StreamOption.Kind.AUDIO,
                    resolution = null,
                    fps = null,
                    format = "M4A",
                    bitrateKbps = AUDIO_BITRATE.getValue(a.getInt("id")),
                    url = url,
                    referer = REFERER,
                    backupUrls = streamBackups(a),
                    cookie = cookieHeader,
                )
            }
            .sortedByDescending { it.bitrateKbps }
    }

    /** 最高音质轨作为高清视频的配套音频（B站 DASH 音频都是 m4a） */
    private fun pickBestAudio(dash: JSONObject): Triple<String, String, List<String>>? {
        val arr = dash.optJSONArray("audio") ?: return null
        val best = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getInt("id") in AUDIO_BITRATE }
            .maxByOrNull { it.getInt("id") } ?: return null
        val url = streamUrl(best) ?: return null
        return Triple(url, "M4A", streamBackups(best))
    }

    private fun streamUrl(json: JSONObject): String? =
        json.optString("baseUrl").ifEmpty { json.optString("base_url") }.ifEmpty { null }

    private fun streamBackups(json: JSONObject): List<String> {
        val arr = json.optJSONArray("backupUrl") ?: json.optJSONArray("backup_url") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.getString(it).ifEmpty { null } }
    }

    private fun isAvc(v: JSONObject) = v.optString("codecs").startsWith("avc")

    private fun qualityLabel(id: Int) = when (id) {
        16 -> "360p"
        32 -> "480p"
        64 -> "720p"
        80 -> "1080p"
        112 -> "1080p+"
        116 -> "1080P60"
        120 -> "4K"
        125 -> "HDR"
        126 -> "杜比视界"
        127 -> "8K"
        else -> "${id}p"
    }

    private val AUDIO_BITRATE = mapOf(
        30216 to 64,
        30232 to 132,
        30280 to 192,
    )

    private fun expandUrl(url: String): String {
        if (!url.contains("b23.tv")) return url
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = url
        repeat(5) {
            val req = Request.Builder().url(current).header("User-Agent", UA).build()
            noRedirect.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location") ?: return current
                    current = if (loc.startsWith("http")) loc
                    else URL(URL(current), loc).toString()
                } else {
                    return current
                }
            }
        }
        return current
    }

    private fun apiGet(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .apply { cookieHeader?.let { header("Cookie", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("B站接口请求失败 HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("B站接口返回为空")
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("B站接口返回异常（可能被风控），请稍后再试") }
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IOException(
                    when (code) {
                        -412 -> "被B站风控拦截了，请稍等几分钟再试"
                        -404 -> "视频不存在或已失效"
                        -101 -> "B站需要登录才能看这个视频"
                        else -> "B站接口错误 $code：${json.optString("message")}"
                    }
                )
            }
            return json.getJSONObject("data")
        }
    }

    /** wbi 签名：参数排序 + mixin 置换 + md5 */
    private fun signWbi(params: TreeMap<String, String>): String {
        val (imgKey, subKey) = fetchWbiKeys()
        val total = imgKey + subKey
        val mixin = MIXIN.joinToString("") { total[it].toString() }.take(32)
        params["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val rid = md5(query + mixin)
        return "$query&w_rid=$rid"
    }

    private fun fetchWbiKeys(): Pair<String, String> {
        cachedWbiKeys?.let { return it }
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/nav")
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("获取B站签名密钥失败")
            val wbi = JSONObject(body).getJSONObject("data").getJSONObject("wbi_img")
            val imgKey = wbi.getString("img_url").substringAfterLast('/').substringBefore('.')
            val subKey = wbi.getString("sub_url").substringAfterLast('/').substringBefore('.')
            cachedWbiKeys = imgKey to subKey
            return imgKey to subKey
        }
    }

    /** 与B站前端 encodeURIComponent 的保留字符集保持一致 */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%2A", "*")
            .replace("%7E", "~")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
