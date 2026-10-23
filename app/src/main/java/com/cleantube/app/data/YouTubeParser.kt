package com.cleantube.app.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** NewPipeExtractor 移除了内置实现，这里用 OkHttp 提供网络层 */
private class OkHttpDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
        request.headers().forEach { (key, values) ->
            // OkHttp 会自己设置 Content-Type/Content-Length，重复设置会崩
            if (key.equals("Content-Type", true) || key.equals("Content-Length", true)) return@forEach
            values.forEach { builder.addHeader(key, it) }
        }
        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", USER_AGENT)
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("Too many requests", request.url())
            val respBody = resp.body?.string() ?: ""
            return Response(resp.code, resp.message, resp.headers.toMultimap(), respBody, resp.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

object YouTubeParser {

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInit() {
        if (initialized) return
        NewPipe.init(
            OkHttpDownloader(),
            Localization("en"),
            ContentCountry("US")
        )
        initialized = true
    }

    /**
     * 解析 YouTube 链接为视频信息 + 可选下载流。阻塞网络调用。
     */
    fun parse(rawUrl: String): VideoResult {
        ensureInit()
        val url = normalizeUrl(rawUrl)
        val service = ServiceList.YouTube
        val info = StreamInfo.getInfo(service, url)

        val downloadable = { s: org.schabi.newpipe.extractor.stream.Stream ->
            s.content.isNotEmpty() &&
                (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.DASH)
        }

        val progressive = info.videoStreams
            .filter { !it.isVideoOnly && downloadable(it) }
            .distinctBy { it.id }
            .map { it.toOption(StreamOption.Kind.PROGRESSIVE) }

        val audioStreams = info.audioStreams
            .filter { downloadable(it) }
            .distinctBy { it.id }
            .sortedByDescending { it.averageBitrate }

        val videoOnly = info.videoOnlyStreams
            .filter { downloadable(it) }
            .distinctBy { it.id }
            .map { video ->
                val companion = pickCompanionAudio(audioStreams, video)
                video.toOption(StreamOption.Kind.VIDEO_ONLY, companion)
            }

        val audio = audioStreams.map {
            StreamOption(
                itag = it.id,
                kind = StreamOption.Kind.AUDIO,
                resolution = null,
                fps = null,
                format = displayFormat(it.format),
                bitrateKbps = it.averageBitrate,
                url = it.content,
            )
        }

        val resolutionRank: (StreamOption) -> Int = { opt ->
            opt.resolution?.removeSuffix("p")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        }
        val options = (progressive + videoOnly).sortedByDescending(resolutionRank) + audio
        check(options.isNotEmpty()) { "没有找到可下载的视频流（可能是会员专享或直播）" }

        return VideoResult(
            id = info.id,
            platform = Platform.YOUTUBE,
            title = info.name,
            uploader = info.uploaderName ?: "",
            durationSec = info.duration,
            thumbnailUrl = info.thumbnails.lastOrNull()?.url ?: "",
            options = options,
        )
    }

    private fun pickCompanionAudio(
        sortedAudio: List<AudioStream>,
        video: VideoStream,
    ): AudioStream? {
        val wantMp4 = isMp4Family(video.format)
        return sortedAudio.firstOrNull { isMp4Family(it.format) == wantMp4 }
            ?: sortedAudio.firstOrNull()
    }

    private fun isMp4Family(format: MediaFormat?) = when (format) {
        MediaFormat.MPEG_4, MediaFormat.M4A -> true
        else -> false
    }

    private fun displayFormat(format: MediaFormat?): String = when (format) {
        MediaFormat.MPEG_4 -> "MP4"
        MediaFormat.M4A -> "M4A"
        MediaFormat.WEBM, MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "WEBM"
        MediaFormat.MP3 -> "MP3"
        else -> format?.name?.uppercase(Locale.US) ?: "BIN"
    }

    private fun VideoStream.toOption(
        kind: StreamOption.Kind,
        companion: AudioStream? = null,
    ) = StreamOption(
        itag = id,
        kind = kind,
        resolution = resolution,
        fps = fps,
        format = displayFormat(format),
        bitrateKbps = null,
        url = content,
        companionAudioUrl = companion?.content,
        companionFormat = companion?.let { displayFormat(it.format) },
    )

    /** 用户可能粘贴 youtu.be/xxx、带参数的分享链接等 */
    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http")) {
            trimmed
        } else {
            "https://www.youtube.com/watch?v=$trimmed"
        }
    }

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return "--:--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
