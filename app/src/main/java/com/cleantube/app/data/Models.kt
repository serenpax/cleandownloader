package com.cleantube.app.data

enum class Platform { YOUTUBE, BILIBILI }

data class StreamOption(
    val itag: String,
    val kind: Kind,
    /** "1080p" 等；仅音频时为 null */
    val resolution: String?,
    val fps: Int?,
    /** "MP4" / "WEBM" / "M4A" */
    val format: String,
    /** 仅音频时的码率（kbps） */
    val bitrateKbps: Int?,
    val url: String,
    /** 仅视频流：用于合并的最佳音频流地址 */
    val companionAudioUrl: String? = null,
    val companionFormat: String? = null,
    /** B站等站点的 CDN 需要带 Referer 才允许下载 */
    val referer: String? = null,
    /** B站 DASH 流附带的备用 CDN 地址，主链失败时依次重试 */
    val backupUrls: List<String> = emptyList(),
    /** 配套音频的备用 CDN 地址 */
    val backupAudioUrls: List<String> = emptyList(),
    /** 登录后部分高清流的 CDN 需要带 Cookie */
    val cookie: String? = null,
) {
    enum class Kind {
        /** 视频+音频一体流，直接下载即可播放 */
        PROGRESSIVE,
        /** 仅视频（自适应流，1080p 及以上），需合并音频 */
        VIDEO_ONLY,
        /** 仅音频 */
        AUDIO,
    }
}

data class VideoResult(
    val id: String,
    val platform: Platform,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String,
    val options: List<StreamOption>,
)
