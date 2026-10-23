package com.cleantube.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.cleantube.app.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

data class DownloadTask(
    val key: String,
    val title: String,
    val label: String,
    val stage: Stage,
    val progress: Float,
    val detail: String? = null,
) {
    enum class Stage { DOWNLOADING, MERGING, SAVING, DONE, FAILED }
    val active: Boolean get() = stage != Stage.DONE && stage != Stage.FAILED
}

object DownloadEngine {

    private lateinit var appContext: Context
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun start(video: VideoResult, option: StreamOption) {
        init(appContextOrNull() ?: return)
        val key = "${video.id}_${option.itag}"
        if (_tasks.value.any { it.key == key && it.stage != DownloadTask.Stage.FAILED }) return
        val label = buildLabel(option)
        update(DownloadTask(key, video.title, label, DownloadTask.Stage.DOWNLOADING, 0f))
        startService()
        scope.launch {
            try {
                runDownload(key, video.title, option)
            } catch (e: Exception) {
                update(
                    DownloadTask(
                        key, video.title, label,
                        DownloadTask.Stage.FAILED, 0f,
                        e.message ?: e.javaClass.simpleName,
                    )
                )
                cleanCache()
            }
        }
    }

    fun remove(key: String) {
        _tasks.update { list -> list.filterNot { it.key == key } }
    }

    private fun appContextOrNull(): Context? =
        if (::appContext.isInitialized) appContext else null

    private fun startService() {
        val intent = Intent(appContext, DownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }
    }

    private fun runDownload(key: String, title: String, option: StreamOption) {
        val cacheDir = File(appContext.cacheDir, "dl").apply { mkdirs() }
        val safeTitle = sanitize(title)
        val container = when {
            option.kind == StreamOption.Kind.AUDIO ->
                if (option.format == "M4A") "m4a" else "webm"
            option.format == "MP4" -> "mp4"
            else -> "webm"
        }

        val mainFile = File(cacheDir, "${key}_main.$container")
        downloadFirst(listOf(option.url) + option.backupUrls, mainFile, option) { p ->
            setProgress(key, p * 0.8f)
        }

        val finalFile: File
        val mime: String
        if (option.kind == StreamOption.Kind.VIDEO_ONLY) {
            val audioUrl = requireNotNull(option.companionAudioUrl) { "缺少配套音频流" }
            val audioContainer = option.companionFormat?.lowercase() ?: "m4a"
            val audioFile = File(cacheDir, "${key}_audio.$audioContainer")
            val audioOption = option.copy(url = audioUrl)
            downloadFirst(listOf(audioUrl) + option.backupAudioUrls, audioFile, audioOption) { p ->
                setProgress(key, 0.8f + p * 0.1f)
            }

            setStage(key, DownloadTask.Stage.MERGING, 0.92f)
            finalFile = File(cacheDir, "${key}_merged.$container")
            if (!mux(mainFile, audioFile, finalFile, isMp4 = container == "mp4")) {
                throw IOException("音视频合并失败")
            }
            mainFile.delete()
            audioFile.delete()
            mime = if (container == "mp4") "video/mp4" else "video/webm"
        } else {
            finalFile = mainFile
            mime = when (container) {
                "mp4" -> if (option.kind == StreamOption.Kind.AUDIO) "audio/mp4" else "video/mp4"
                "m4a" -> "audio/mp4"
                "webm" -> if (option.kind == StreamOption.Kind.AUDIO) "audio/webm" else "video/webm"
                else -> "application/octet-stream"
            }
        }

        setStage(key, DownloadTask.Stage.SAVING, 0.96f)
        val displayName = "${safeTitle}_${displaySuffix(option)}.$container"
        val size = finalFile.length()
        val isVideo = option.kind != StreamOption.Kind.AUDIO
        val savedUri = saveToMediaStore(finalFile, mime, displayName, isVideo)
            ?: throw IOException("保存到系统相册/媒体库失败")
        finalFile.delete()

        update(
            DownloadTask(
                key, title, label = buildLabel(option),
                stage = DownloadTask.Stage.DONE, progress = 1f,
                detail = if (isVideo) "已保存到 Movies/CleanTube" else "已保存到 Music/CleanTube",
            )
        )
        HistoryStore.add(
            HistoryRecord(
                key = key,
                title = title,
                label = buildLabel(option),
                displayName = displayName,
                uri = savedUri.toString(),
                isVideo = isVideo,
                sizeBytes = size,
                timeMillis = System.currentTimeMillis(),
            )
        )
        cleanCache()
    }

    /** Android 系统 API 无损合并（不重新编码），兼容任意内核页大小 */
    private fun mux(video: File, audio: File, out: File, isMp4: Boolean): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw IOException("视频文件中找不到视频轨")
            val audioTrack = findTrack(audioExtractor, "audio/")
                ?: throw IOException("音频文件中找不到音频轨")

            muxer = MediaMuxer(
                out.absolutePath,
                if (isMp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            )
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()

            copyTrack(videoExtractor, videoTrack, muxer, videoOut)
            copyTrack(audioExtractor, audioTrack, muxer, audioOut)

            muxer.stop()
            return out.exists() && out.length() > 0
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) return i
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outTrack: Int,
    ) {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buf, info)
            if (!extractor.advance()) break
        }
    }

    /** 依次尝试主链和备用 CDN；每条链路失败自动重试3次（指数退避），支持 Range 断点续传 */
    private fun downloadFirst(
        urls: List<String>,
        dest: File,
        option: StreamOption,
        onProgress: (Float) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in urls) {
            var attempt = 0
            while (true) {
                try {
                    fetch(url, dest, option, onProgress)
                    return
                } catch (e: IOException) {
                    lastError = e
                    attempt++
                    if (attempt >= 3) break
                    try {
                        Thread.sleep(1500L * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("下载已取消")
                    }
                }
            }
            // 切换备用 CDN 时从头开始（不同 CDN 的续传偏移不一定对齐）
            dest.delete()
        }
        throw lastError ?: IOException("没有可用的下载地址")
    }

    private fun fetch(url: String, dest: File, option: StreamOption, onProgress: (Float) -> Unit) {
        val existing = if (dest.exists()) dest.length() else 0L
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                option.referer?.let { header("Referer", it) }
                option.cookie?.let { header("Cookie", it) }
                if (existing > 0) header("Range", "bytes=$existing-")
            }
            .build()
        client.newCall(request).execute().use { resp ->
            val append = when {
                resp.code == 206 -> true
                // 200 = 服务器忽略 Range（或无断点可续），从头下载
                resp.isSuccessful -> false
                else -> throw IOException("下载失败 HTTP ${resp.code}")
            }
            val base = if (append) existing else 0L
            if (!append && existing > 0) dest.delete()
            val body = resp.body ?: throw IOException("下载失败：空响应")
            val total = when {
                append -> parseContentRangeTotal(resp.header("Content-Range"))
                    ?: (base + body.contentLength()).takeIf { it > 0 } ?: -1L
                else -> body.contentLength()
            }
            var downloaded = base
            var lastEmit = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = SystemClock.uptimeMillis()
                        if (total > 0 && now - lastEmit > 200) {
                            onProgress(downloaded.toFloat() / total)
                            lastEmit = now
                        }
                    }
                }
            }
            onProgress(1f)
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        // 形如 "bytes 100-999/1234"
        val total = header?.substringAfter('/', "")?.trim()
        return total?.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun saveToMediaStore(
        file: File,
        mime: String,
        displayName: String,
        isVideo: Boolean,
    ): Uri? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES + "/CleanTube"
                    else Environment.DIRECTORY_MUSIC + "/CleanTube"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun buildLabel(option: StreamOption): String = when (option.kind) {
        StreamOption.Kind.AUDIO ->
            "仅音频 ${option.bitrateKbps ?: 0}kbps ${option.format}"
        else -> {
            val fps = option.fps?.takeIf { it > 30 }?.let { "${it}fps" } ?: ""
            "${option.resolution.orEmpty()} $fps ${option.format}".trim()
        }
    }

    private fun displaySuffix(option: StreamOption): String = when (option.kind) {
        StreamOption.Kind.AUDIO -> "Audio"
        else -> option.resolution ?: option.itag
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_")
            .trim()
            .take(80)
            .ifEmpty { "video" }

    private fun setProgress(key: String, p: Float) =
        updateStage(key, DownloadTask.Stage.DOWNLOADING, p.coerceIn(0f, 1f), null)

    private fun setStage(key: String, stage: DownloadTask.Stage, p: Float) =
        updateStage(key, stage, p, null)

    private fun updateStage(key: String, stage: DownloadTask.Stage, p: Float, detail: String?) {
        _tasks.update { list ->
            list.map {
                if (it.key == key) it.copy(stage = stage, progress = p, detail = detail ?: it.detail)
                else it
            }
        }
    }

    private fun update(task: DownloadTask) {
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.key == task.key }
            if (idx >= 0) list.toMutableList().apply { this[idx] = task } else list + task
        }
    }

    private fun cleanCache() {
        File(appContext.cacheDir, "dl").listFiles()?.forEach { it.delete() }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
}
