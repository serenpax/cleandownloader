# CleanDownloader

无广告、无弹窗的视频下载 Android App。支持 **YouTube** 和**哔哩哔哩**。粘贴链接（或从对应 App「分享」进来）→ 选画质 → 下载到本地。

## 功能

- 粘贴（或从 YouTube / B站 App「分享」到 CleanDownloader）视频链接，自动识别平台：
  - YouTube：普通视频、Shorts
  - 哔哩哔哩：普通视频、分P视频（`?p=n` 指定分P）、`b23.tv` 短链自动展开
- 自动解析全部可下载流，按画质分组展示：
  - **标准画质**（YouTube 视频+音频一体流，直接下载即可播放）
  - **高清画质**（无声视频轨，下载时自动合并音频）：
    - YouTube：1080p ~ 4320p（MP4/WEBM）
    - B站：360p ~ 8K（未登录最高 1080p，自动选 avc1 编码保证兼容性）
  - **仅音频**（YouTube M4A/WEBM；B站 M4A 64/132/192kbps）
- 实时下载进度、合并进度
- **后台下载**：前台服务加持，切后台 / 锁屏继续下，通知栏实时显示进度，完成后自动通知
- **断点续传 + 自动重试**：网络中断后从断点继续，失败自动重试 3 次（指数退避）
- **下载历史**：顶栏时钟图标进入，记录标题/画质/大小/时间，点条目直接播放，可删单条或清空
- **B站扫码登录**：顶栏头像图标打开，解锁 1080P+ / 4K；Cookie 仅存本机，可随时退出
- B站下载自动带 Referer，主 CDN 失败自动切换备用 CDN
- 视频保存到 `Movies/CleanTube/`，音频保存到 `Music/CleanTube/`（系统媒体库可见）
- 深色模式跟随系统
- 无任何广告、无跟踪、不需要账号

## 技术栈

| 组件 | 用途 |
| --- | --- |
| Kotlin + Jetpack Compose (Material 3) | UI |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) v0.26.5 | YouTube 链接解析（签名解密、画质流枚举） |
| B站公开 API + wbi 签名 | B站视频解析（view API + playurl DASH 流） |
| OkHttp | 网络层（解析 + 下载） |
| Android MediaMuxer / MediaExtractor | 音视频无损合并（系统 API，不转码，无 ffmpeg 二进制） |
| MediaStore | 保存到系统媒体库（Android 10+ 无需任何存储权限） |

## 编译

需要 JDK 17 + Android SDK（API 35）。

```powershell
.\gradlew.bat assembleDebug      # 调试版（全架构）
.\gradlew.bat assembleRelease    # 发布版（已配置签名，R8 压缩，~3.3MB）
# 产物：app\build\outputs\apk\release\app-release.apk
```

安装到手机：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

> Release 使用项目内的 `release.keystore` 签名（密码在 `keystore.properties`，均已 gitignore）。备份这两个文件，否则以后无法覆盖安装升级。

## 使用

1. 在 YouTube / B站 App 里点「分享」→ 选择 CleanDownloader（或在 App 里粘贴链接）
2. 点「解析视频」
3. 选择画质，点该行右侧的下载按钮
4. 等待进度条走完（高清画质会先下载视频轨和音频轨，再自动合并）

## 说明与限制

- 仅用于**个人学习研究**，请勿商业使用。下载内容的版权归属各平台/上传者。
- NewPipeExtractor 为 GPL-3.0 开源项目；若要分发本 App 的二进制，需遵守其开源协议。
- 直播、会员专享、需要登录的视频无法下载；B站未登录最高 1080p。
- 若某个视频解析失败（-412 风控等），稍等几分钟再试；YouTube 改版则需升级 NewPipeExtractor。
- 抖音等平台反爬过于激进，暂不支持。

## 项目结构

```
app/src/main/java/com/cleantube/app/
├── MainActivity.kt          # 入口，处理分享/打开链接
├── MainViewModel.kt         # 状态管理，平台路由
├── data/
│   ├── Models.kt            # 数据模型
│   ├── YouTubeParser.kt     # NewPipeExtractor 封装 + OkHttp 网络层
│   ├── BiliParser.kt        # B站解析（b23.tv 展开、wbi 签名、DASH 流）
│   └── DownloadEngine.kt    # 下载、备用CDN重试、MediaMuxer 合并、保存到媒体库
└── ui/
    ├── MainScreen.kt        # 主界面
    └── theme/Theme.kt       # 主题（绿色 Material 3）
```
