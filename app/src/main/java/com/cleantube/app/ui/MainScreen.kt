package com.cleantube.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cleantube.app.MainViewModel
import com.cleantube.app.data.DownloadTask
import com.cleantube.app.data.HistoryRecord
import com.cleantube.app.data.Platform
import com.cleantube.app.data.StreamOption
import com.cleantube.app.data.VideoResult
import com.cleantube.app.data.YouTubeParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val downloads = vm.downloads.collectAsStateWithLifecycle().value

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CleanDownloader", fontWeight = FontWeight.SemiBold)
                        Text(
                            "无广告 · 绿色版",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::openBiliLogin) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "B站登录",
                            tint = if (vm.biliLoggedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = vm::switchScreen) {
                        if (vm.screen == MainViewModel.Screen.HOME) {
                            Icon(Icons.Default.History, contentDescription = "下载历史")
                        } else {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (vm.screen) {
            MainViewModel.Screen.HOME -> HomeContent(vm, downloads, Modifier.padding(padding))
            MainViewModel.Screen.HISTORY -> HistoryContent(vm, Modifier.padding(padding))
        }
    }

    if (vm.showBiliLogin) {
        BiliLoginDialog(vm)
    }
}

@Composable
private fun HomeContent(
    vm: MainViewModel,
    downloads: List<DownloadTask>,
    modifier: Modifier = Modifier,
) {
    val result = vm.result
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinkInputCard(vm)

        vm.error?.let { ErrorCard(it) { vm.dismissError() } }

        result?.let { VideoInfoCard(it) }

        result?.let { r ->
            SectionTitle("选择画质")
            if (vm.biliHint) {
                Text(
                    "B站未登录最高 1080p，点右上角头像扫码登录可解锁 1080P+ / 4K",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            StreamList(r) { vm.download(it) }
        }

        if (downloads.isNotEmpty()) {
            SectionTitle("下载任务")
            downloads.forEach { task ->
                DownloadTaskCard(task, onRemove = { vm.removeTask(task.key) })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "仅用于个人学习研究，请勿用于商业用途。视频保存到 Movies/CleanTube，音频保存到 Music/CleanTube。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistoryContent(vm: MainViewModel, modifier: Modifier = Modifier) {
    val items = vm.history.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已下载 ${items.size} 条",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory) { Text("清空记录") }
            }
        }
        if (items.isEmpty()) {
            Text(
                "还没有下载记录。下载完成的视频会出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.forEach { record ->
            HistoryCard(
                record = record,
                onOpen = { openRecord(context, record) },
                onRemove = { vm.removeHistory(record.key) },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun openRecord(context: Context, record: HistoryRecord) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(record.uri), if (record.isVideo) "video/*" else "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryCard(record: HistoryRecord, onOpen: () -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), onClick = onOpen) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (record.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${record.label} · ${formatSize(record.sizeBytes)} · ${formatTime(record.timeMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "移除记录")
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

@Composable
private fun BiliLoginDialog(vm: MainViewModel) {
    Dialog(onDismissRequest = vm::closeBiliLogin) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("哔哩哔哩登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                when (val ui = vm.biliLogin) {
                    is MainViewModel.BiliLoginUi.Checking -> {
                        CircularProgressIndicator()
                        Text("正在获取二维码…", style = MaterialTheme.typography.bodyMedium)
                    }

                    is MainViewModel.BiliLoginUi.LoggedIn -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Text("已登录：${ui.uname}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "现在可以下载 1080P+ / 4K 等全部画质",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = vm::logoutBili) { Text("退出登录") }
                            Button(onClick = vm::closeBiliLogin) { Text("完成") }
                        }
                    }

                    is MainViewModel.BiliLoginUi.Qr -> {
                        val qr = rememberQrImage(ui.content)
                        if (qr != null && !ui.expired) {
                            Image(
                                qr,
                                contentDescription = "登录二维码",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                                Text("二维码无效", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            ui.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ui.expired) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ui.expired) {
                            Button(onClick = vm::refreshBiliLogin) { Text("刷新二维码") }
                        } else {
                            Text(
                                "登录仅用于解锁高清画质，Cookie 只保存在本机",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is MainViewModel.BiliLoginUi.Error -> {
                        Text(ui.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = vm::refreshBiliLogin) { Text("重试") }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberQrImage(content: String): ImageBitmap? = remember(content) {
    runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bmp.setPixel(
                    x, y,
                    if (matrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE,
                )
            }
        }
        bmp.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun LinkInputCard(vm: MainViewModel) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.link,
                onValueChange = vm::onLinkChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("视频链接（YouTube / 哔哩哔哩）") },
                placeholder = { Text("https://youtu.be/... 或 b23.tv/...") },
                singleLine = true,
                enabled = !vm.parsing,
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            )
            Button(
                onClick = vm::parse,
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.parsing,
            ) {
                if (vm.parsing) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("解析中…")
                } else {
                    Text("解析视频")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun VideoInfoCard(video: VideoResult) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 144.dp, height = 81.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        video.uploader.takeIf { it.isNotEmpty() },
                        YouTubeParser.formatDuration(video.durationSec),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StreamList(video: VideoResult, onDownload: (StreamOption) -> Unit) {
    val progressive = video.options.filter { it.kind == StreamOption.Kind.PROGRESSIVE }
    val videoOnly = video.options.filter { it.kind == StreamOption.Kind.VIDEO_ONLY }
    val audio = video.options.filter { it.kind == StreamOption.Kind.AUDIO }

    if (progressive.isNotEmpty()) {
        GroupLabel("标准画质 · 视频 + 音频")
        progressive.forEach { StreamRow(it, onDownload) }
    }
    if (videoOnly.isNotEmpty()) {
        GroupLabel("高清画质 · 自动合并音频")
        videoOnly.forEach { StreamRow(it, onDownload) }
    }
    if (audio.isNotEmpty()) {
        GroupLabel("仅音频")
        audio.forEach { StreamRow(it, onDownload) }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StreamRow(option: StreamOption, onDownload: (StreamOption) -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (option.kind) {
                    StreamOption.Kind.AUDIO -> Icons.Default.MusicNote
                    StreamOption.Kind.VIDEO_ONLY -> Icons.Default.Movie
                    StreamOption.Kind.PROGRESSIVE -> Icons.Default.Download
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    primaryLabel(option),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    secondaryLabel(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onDownload(option) }) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun primaryLabel(o: StreamOption): String = when (o.kind) {
    StreamOption.Kind.AUDIO -> "${o.bitrateKbps ?: 0} kbps"
    StreamOption.Kind.VIDEO_ONLY -> "${o.resolution ?: ""}${o.fps?.takeIf { it > 30 }?.let { " $it" } ?: ""}"
    StreamOption.Kind.PROGRESSIVE -> o.resolution ?: "标清"
}

private fun secondaryLabel(o: StreamOption): String = when (o.kind) {
    StreamOption.Kind.AUDIO -> "${o.format} 音频"
    StreamOption.Kind.VIDEO_ONLY -> "${o.format} · 无声画质，下载时自动合并音频"
    StreamOption.Kind.PROGRESSIVE -> "${o.format} · 可直接播放"
}

@Composable
private fun DownloadTaskCard(task: DownloadTask, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        task.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (task.stage == DownloadTask.Stage.DONE || task.stage == DownloadTask.Stage.FAILED) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Close, contentDescription = "移除")
                    }
                }
            }
            when (task.stage) {
                DownloadTask.Stage.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "下载中 ${(task.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadTask.Stage.MERGING -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在合并音视频…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadTask.Stage.SAVING -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在保存到媒体库…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadTask.Stage.DONE -> {
                    Text(
                        "✓ ${task.detail ?: "完成"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DownloadTask.Stage.FAILED -> {
                    Text(
                        "✕ 失败：${task.detail ?: "未知错误"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
