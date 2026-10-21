# 影視TV（AITV）项目总结

> 分析对象：`D:\codes\AITV`  
> 分支 / 提交：`fongmi` / `0af3b31a1`（2026-10-18）  
> 版本：`5.6.3`（versionCode `563`）  
> 上游：FongMi/TV（README 品牌名「影視TV」）  
> 许可证：GPL-3.0  
> 代码规模：约 541 个 `.java` 文件、4.9 万行 Java（不含构建脚本、资源、Python）

---

## 0. 一页速览

| 维度 | 结论 |
| --- | --- |
| 产品形态 | Android TV + Android 手机双端影音播放器（空壳客户端） |
| 内容来源 | **App 不内置内容**，完全由用户配置的 JSON / M3U / TXT + 爬虫（JAR/JS/PY）提供 |
| 核心能力 | 点播聚合、直播/EPG/回看、多播放引擎、字幕/弹幕、DLNA、Android Auto、局域网 Web 遥控 |
| 技术栈 | Java 21、Gradle 9.7.1、AGP 9.3.1、compileSdk/targetSdk 37、minSdk 24、ViewBinding + LiveData、Room、OkHttp、Media3（自编译分支）、mpv、QuickJS、Chaquopy Python 3.10 |
| 工程结构 | 4 个 Gradle 模块：`:app`、`:catvod`、`:chaquo`、`:quickjs`；另有 6 个历史/预编译模块目录 |
| 最大构建门槛 | 上游仓库不含 `lib-*.aar`；**本工作副本已用公开 FongMi/media 重建 AAR + 7 个兼容类完成构建** |
| 扩展核心 | 配置驱动 + Spider 三运行时 + Parse 五模式 + 本地 HTTP API |
| 主要风险 | 文档超前于代码、配置即代码（任意 JAR/JS/PY 执行）、局域网 HTTP 无鉴权、GPL 传染性 |

一句话：**这是一个“播放器内核 + 配置协议 + 爬虫运行时”的壳工程，二次开发的重点不在播放器本身，而在配置协议、站点适配、UI 定制和本地服务扩展。**

---

## 1. 业务

### 1.1 定位与边界

- **播放器/聚合客户端**：负责浏览、搜索、详情、播放、记录、收藏、投屏、同步。
- **不提供内容**：没有内置片源；首次安装无任何可看内容，必须导入配置。
- **内容即配置**：一个 JSON 配置就能改变首页、站点列表、解析器、直播源、壁纸、网络策略。
- **多配置并存**：点播配置、直播配置、壁纸配置分别存储（`Config.type = 0/1/2`），支持 Depot 聚合多个配置 URL。
- **跨端**：TV 端偏遥控器/大屏，手机端偏触控/PiP/扫码/投屏。

### 1.2 业务闭环

```text
导入配置(JSON URL)
   → 解析 sites / parses / lives / wallpaper / rules
   → 首页推荐 / 分类筛选 / 跨站搜索
   → 详情(剧集/线路/画质/字幕/弹幕)
   → 选剧集 → 站点取播放地址(playerContent)
   → 直链或解析(parse) → 播放器引擎(Exo/mpv)
   → 记录进度/收藏/跳过片头片尾/预加载下一集
   → 备份、跨设备同步、DLNA 投屏、网页遥控
```

### 1.3 用户可见功能矩阵

| 域 | 能力 | 主要实现 |
| --- | --- | --- |
| 点播 | 首页、分类、筛选、详情、多线路、多画质、剧集反转、自动换源 | `SiteApi`、`VodPlaybackController` |
| 搜索 | 单站搜索、全站并发搜索、快捷搜索、搜索建议 | `ViewModelSearchRunner`、`SearchActivity` |
| 直播 | M3U/TXT/JSON、分组、收藏频道、EPG、回看/时移、多线路 | `LiveConfig`、`LiveParser`、`EpgParser`、`LivePlaybackController` |
| 播放 | ExoPlayer、mpv、硬解/软解、隧道、AV1/FFmpeg/Opus/Flac、DRM、SMB/RTMP/RTSP | `PlayerEngineFactory`、`ExoMediaSourceFactory`、`MpvPlayerEngine` |
| 画质增强 | 视频亮度/对比度/饱和度/锐化/色温/阴影/Gamma、音频均衡器/响度/对白增强/声道 | `player/effect/**` |
| 字幕 | 内嵌/外挂、SRT/SSA/ASS/VTT、libass、双字幕、样式/字体/位置/延迟、Assrt 搜索 | `SubtitleSetting`、`ExoSubtitleController`、`SubtitleApi` |
| 弹幕 | 弹幕 API、自动匹配、时间偏移、样式、透明度、速度、发送 | `DanmakuApi`、`DanmakuSetting`、`DanmakuPlayerViewController` |
| 片头片尾 | 手动设置、来源下发 `skips`、自动跳过 | `VodHistoryPolicy`、`Result` |
| 记录 | 播放进度、最近观看、收藏、无痕模式、反向播放 | Room `History` / `Keep` |
| 预加载 | 下一集预载、磁盘缓存、线程/大小/时长配置 | `PreloadSetting`、`ExoDiskPreload`、`VodPreloader` |
| 投屏 | 手机 DLNA 投放，TV 作为 DLNA Renderer 接收 | mobile `dlna/**`、leanback `dlna/**` |
| 车机 | Android Auto 浏览记录/收藏/直播/搜索 | `PlaybackService` + `browse/**` |
| 局域网 | Web 控制台、推送、文件管理、缓存、代理、媒体状态、设备信息 | `Nano` + `server/process/**` |
| 同步 | 历史/收藏在设备间同步 | `/action?do=sync` |
| 备份 | 退出时自动备份（同日覆盖）、最多 7 份、恢复 | `BackupManager`、`bean/Backup` |
| 外观 | 壁纸（图片/GIF/视频）、主题色、动态取色、拼音/注音键盘 | `WallConfig`、`Setting` |
| 更新 | 检查 GitHub Release、下载 APK、安装 | `Updater`、`Github` |

### 1.4 分发形态

- 两个产品风味：`leanback`（TV）、`mobile`（手机）。
- 两个 ABI：`arm64-v8a`、`armeabi-v7a`；不生成 universal APK。
- 产物：`Release/apk/leanback-arm64_v8a.apk`、`leanback-armeabi_v7a.apk`、`mobile-arm64_v8a.apk`、`mobile-armeabi_v7a.apk`。
- 更新通道：`https://raw.githubusercontent.com/FongMi/Release/fongmi`（JSON + 按风味/ABI 命名的 APK）。

### 1.5 典型用户旅程

1. 安装 APK。
2. 设置页粘贴配置 URL（或扫码/推送）。
3. 配置拉取成功后保存配置元数据与站点偏好，首页出现站点与推荐。
4. 选片 → 详情 → 选线路/剧集 → 播放。
5. 若为网页源：内置 WebView 嗅探/解析出真实媒体地址。
6. 退出自动存进度；下次继续；可收藏、投屏、手机遥控。

---

## 2. 工程技术结构

### 2.1 关键参数

| 项 | 值 | 位置 |
| --- | --- | --- |
| 语言 | Java 21（无 Kotlin） | `app/build.gradle` |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.3.1 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 37 / 37 | 同上 |
| minSdk | 24（Android 7.0） | 同上 |
| applicationId / namespace | `com.fongmi.android.tv` | `app/build.gradle` |
| 版本 | 5.6.3 / 563 | 同上 |
| UI | ViewBinding + Leanback + Material，无 Compose | `buildFeatures.viewBinding` |
| 异步 | LiveData + Guava `ListenableFuture` + 自定义 `Task` | `model/**`、`utils/Task` |
| 数据库 | Room 2.8.4，schema v35，7 张表 | `db/**`、`app/schemas` |
| 网络 | OkHttp 5.5.0（DoH/Proxy/Hosts/Header/Rule） | `catvod/net/**` |
| 播放 | Media3（定制 AAR）+ mpv（定制 AAR） | `player/**` |
| 脚本 | QuickJS 3.2.3、Chaquopy 17 / Python 3.10 | `quickjs/**`、`chaquo/**` |
| 本地服务 | NanoHTTPD 2.3.1，端口 9978–9998 | `server/**` |
| 投屏 | JUPnP 3.0.5 | `dlna/**` |

### 2.2 Gradle 模块

`settings.gradle` 实际参与构建的只有 4 个：

```text
:app        Android 应用（main + leanback + mobile 三套 sourceSet）
:catvod     爬虫/网络基础库（OkHttp、Path、Crypto、Json、Trans、Web 代理）
:chaquo     Python 运行时桥（Chaquopy 3.10）
:quickjs    JavaScript 运行时桥（QuickJS）
```

仓库中另有 6 个目录，**未 include 进 Gradle**，属于“历史源码 / 供单独打 AAR”：

| 目录 | 作用 | 当前使用方式 |
| --- | --- | --- |
| `hook/` | 伪造 `PackageManager`（包名、签名、权限） | 通过 `app/libs/hook-release.aar` |
| `thunder/` | 迅雷下载引擎封装（magnet/ed2k/torrent） | 通过 `app/libs/thunder-release.aar` |
| `jianpian/` | 人人/ JianPian P2P 播放 | 通过 `app/libs/jianpian-release.aar` |
| `tvbus/` | TVBus P2P 直播内核 | 通过 `app/libs/tvbus-release.aar` |
| `forcetech/` | ForceTech/mitv/p2p 协议播放 | 通过 `app/libs/forcetech-release.aar` |
| `zlive/` | JNA 调用 `libcore.so` | **源码未被任何地方引用，疑似废弃** |

`app/libs` 现有 5 个 AAR（约 10 MB）：`forcetech`、`hook`、`jianpian`、`thunder`、`tvbus`。

> ⚠️ **缺失 `lib-*.aar`**：`README` 明确说明 `lib-*.aar` 未纳入 Git，`app/libs/.gitignore` 也忽略它们。代码大量引用定制 Media3 API（`androidx.media3.mpvplayer.MpvPlayer`、`androidx.media3.exoplayer.libass.*`、`androidx.media3.ui.danmaku.DanmakuConfig`、`MediaChapter`、`MediaEdition`、`DecodeTrackSelector` 等），**用官方 Media3 依赖无法编译**。本工作副本已从公开 `FongMi/media` 重建 21 个 AAR，并增加 7 个兼容类；详见 `BUILD_REPORT.md`。

### 2.3 代码分布

| 区域 | 文件数 | 行数（约） | 说明 |
| --- | --- | --- | --- |
| `app/src/main` | 270 | 26,100 | 共享 UI、播放、配置、DB、服务、工具 |
| `app/src/leanback` | 98 | 10,400 | TV 端 UI + DLNA Renderer |
| `app/src/mobile` | 95 | 9,500 | 手机端 UI + DLNA 发送 + 扫码/PiP |
| `catvod` | 27 | 1,900 | 基础库 |
| `quickjs` | 11 | 850 | JS 桥 |
| `chaquo` | 3 + Python | 290 | Python 桥 |
| `thunder` | 22 | 900 | 迅雷封装源码（未构建） |

资源规模：`main` 20 个布局、`leanback` 82 个、`mobile` 90 个；字符串默认英文 + `values-zh-rCN` + `values-zh-rTW`。

### 2.4 目录职责（`com.fongmi.android.tv`）

```text
api/           站点/直播/字幕/弹幕 API + 配置解析 + 三类爬虫 Loader
bean/          Gson/Room/Parcelable 数据模型（Config/Site/Vod/Live/History…）
browse/        Android Auto 媒体树（VOD/Live/Search）
db/            Room Database、DAO、迁移、备份
dlna/          DLNA 通用配置/流服务（JUPnP）
event/         EventBus 事件（配置/刷新/服务/投屏/状态）
impl/          回调接口
model/         ViewModel（Site/Video/Live）+ 并发任务执行器
playback/      播放状态机：VOD/Live 控制器、Host 接口、状态、策略、预加载
player/        播放编排、引擎（Exo/mpv）、提取器、音视频效果、字幕、轨道、解析
receiver/      广播接收器（播放控制）
server/        NanoHTTPD 本地服务及 7 个 Process
service/       Media3 MediaLibraryService（后台播放/车机/通知）
setting/       偏好设置封装
ui/            公共 Activity/Dialog/Adapter/CustomView
utils/         下载、文件、网络、通知、权限、嗅探、字幕压缩等
```

### 2.5 构建与打包流程

1. `local.properties` 提供 SDK 与签名：
   ```properties
   sdk.dir=D:/Scoop/apps/android-clt/current
   storeFile=D:/keys/yingshi-tv.jks
   keyAlias=your-key-alias
   storePassword=your-keystore-password
   ```
   注意：`keyPassword` 与 `storePassword` 共用 `storePassword`。
2. `AbiApkPackaging`（`buildSrc`）配置 ABI split、输出命名；`FinalizeApksTask` 在打包后：
   - 删除另一 ABI 的 Chaquopy 资产（`requirements-*.imy`、`stdlib-*.imy`、`bootstrap-native/<abi>`），显著减小包体；
   - `zipalign -P 16 -f 4`（16 KB 页对齐）；
   - `apksigner` 重新签名，**只启用 v2**（v1/v3/v4 关闭）。
3. `assembleLeanbackRelease` / `assembleMobileRelease` 后，APK 复制到根目录 `Release/apk/`。
4. R8 开启：`minifyEnabled`、`shrinkResources`，规则集中在 `proguard-rules.pro`、`proguard-rules-media.pro`。

```powershell
$env:JAVA_HOME = 'D:\IDE\scoop\apps\temurin21-jdk\current'
.\gradlew.bat :app:assembleLeanbackRelease
.\gradlew.bat :app:assembleMobileRelease
```

### 2.6 构建状态（已实测）

| 项 | 状态 |
| --- | --- |
| `:app:assembleLeanbackDebug/Release` | ✅ 已成功（2026-10-21） |
| `:app:assembleMobileRelease` | ✅ 已成功 |
| 产物 | `Release/apk/{leanback,mobile}-{arm64_v8a,armeabi_v7a}.apk` |
| 签名 | v2 通过（当前为临时测试 keystore） |
| `lib-*.aar` | 已用公开 `FongMi/media` 重建 19 个 + Actions 的 libass + 官方 database，共 21 个放入 `app/libs`（Git 忽略） |
| 7 个缺失类 | 已在 `app/src/main/java/androidx/media3/**` 增加兼容实现 |
| Python 3.10 | 已安装到 `D:\IDE\python310`；`chaquo/build.gradle` 支持 `-PbuildPython` |

> 详细命令、兼容层说明、功能降级与复现步骤见 **`BUILD_REPORT.md`**。  
> 上游仓库仍然缺 `lib-*.aar`；本工作副本的 AAR 与兼容层属于本地构建资产/改动，提交代码时不要把 AAR 一起提交（`.gitignore` 已忽略）。

构建前仍需注意：

- `gradle/gradle-daemon-jvm.properties` pin 了 JetBrains JDK 21；本机为 Temurin 21，构建时移走或安装 JetBrains JDK。
- Maven Central 在本机被 DNS/TLS 污染，使用 `tmp/mirror.init.gradle` 走镜像。

### 2.7 独立助手 App（`assistant/`）

- 独立 Gradle 工程，包名 `com.aitv.assistant`，不加入 AITV 的 `settings.gradle`。
- 功能：局域网扫描/发现、设备缓存与在线标识、播放控制（`±10s/±30s`、进度条跳转、倍速）、推送配置、配置历史管理、点播源搜索、点播详情（播放源/集数选择）、本机 ExoPlayer 播放、频道检索。
- 设备页：扫描结果持久化；用图标颜色区分在线/离线（离线显示最后在线时间），不再展示 leanback/旧接口/扩展等技术字段；离线卡片点击重新探测；左滑露出删除按钮，点击按钮弹确认框，确认后才删除缓存；端口扫描预设 9978–9987 十个端口。
- UI：**Kotlin + Jetpack Compose + Material 3**，动态取色/深浅色主题，Coil 3 加载海报；搜索结果点击进入手机端详情页，可“本机播放”（Media3 ExoPlayer）或“电视播放”（按所选源/集数直接起播）。
- 本机播放：Media3 ExoPlayer 1.8.0 + `media3-ui` PlayerView + HLS/DASH 模块；支持播放器内置控制条、拖动进度和倍速。
- 与 AITV 通过 HTTP 通信：
  - 旧版：`/device`、`/action`、`/media`
  - 新版：`/remote`、`/remote/info`
  - 本仓库扩展：`/assistant/info`、`/assistant/live`、`/assistant/config`、`/assistant/configs`、`/assistant/config/activate`、`/assistant/config/delete`、`/assistant/sites`、`/assistant/search`、`/assistant/vod/detail`、`/assistant/vod/resolve`、`/assistant/vod/play`、`/assistant/vod/open`
- 配置推送会直接写入电视的 Room `Config` 历史表，再加载；助手配置页可以查看当前配置、切换历史配置和删除非当前配置。
- 点播搜索由 AITV 侧并行调用 `SiteApi.searchContent`，详情由 `SiteApi.detailContent` 返回播放源和集数；助手只展示名称，不保存剧集 URL。
- 文档：`ASSISTANT_INTEGRATION.md`；APK：`assistant/app/build/outputs/apk/debug/app-debug.apk`（约 24.5 MB）。

---

## 3. 运行架构

### 3.1 分层视图

```text
┌──────────────────────────────────────────────────────────────┐
│ UI 层（leanback / mobile / main）                             │
│  HomeActivity、VodActivity、VideoActivity、LiveActivity、      │
│  Search/Keep/History/Setting、Dialog、Adapter、CustomView      │
│  —— ViewBinding + Leanback/Material，Activity 承载交互         │
├──────────────────────────────────────────────────────────────┤
│ 状态层                                                        │
│  SiteViewModel / VideoViewModel / LiveViewModel                │
│  LiveData + ViewModelTaskRunner（超时/取消/防串包）             │
├──────────────────────────────────────────────────────────────┤
│ 业务编排层                                                    │
│  SiteApi（取数）                                               │
│  VodPlaybackController（点播状态机 + 选源/换源/预载）           │
│  LivePlaybackController（直播/EPG/回看状态机）                  │
│  PlayerManager（播放会话、引擎切换、解析、轨道、效果）           │
│  Source + Extractor（magnet/tvbus/jianpian/force/push/strm…）  │
├──────────────────────────────────────────────────────────────┤
│ 配置与运行时层                                                │
│  VodConfig / LiveConfig / WallConfig / RuleConfig              │
│  BaseLoader → JarLoader / JsLoader / PyLoader                  │
│  ParseJob（WebView / JSON / Jar 扩展 / Mix / Super）            │
├──────────────────────────────────────────────────────────────┤
│ 基础能力层                                                    │
│  catvod：OkHttp/DoH/Proxy/Hosts/Path/Crypto/Json/Trans         │
│  Room、EventBus、NanoHTTPD、JUPnP、Media3、mpv、QuickJS、Python │
├──────────────────────────────────────────────────────────────┤
│ 平台层：Android Framework / 定制 Media3 AAR / native .so        │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 启动流程

```text
App.attachBaseContext()
  └─ Init.set(base)                     // 给 catvod 注入 Context

App.onCreate()
  ├─ Notify.createChannel()
  └─ registerActivityLifecycleCallbacks

AndroidX Startup → Startup.create()
  ├─ CustomActivityOnCrash 配置
  ├─ Logger 初始化（tag=TV）
  ├─ EventBus 索引安装
  └─ OkHttp.dns().setDoh(Setting.getDoh())

HomeActivity
  ├─ VodConfig/LiveConfig/WallConfig 初始化并加载
  │    └─ BaseConfig.ensureLoaded()/loadConfig()
  │         ├─ Server.get().start()      // 先起本地 HTTP
  │         └─ load(config)              // 拉取并解析配置
  └─ 渲染首页（站点/推荐/历史/功能入口）

VideoActivity / LiveActivity（进入播放时）
  ├─ startService + bindService(LOCAL_BIND_ACTION)
  └─ MediaController 连接
       └─ PlaybackService.onCreate()
            ├─ PlayerManager 创建（默认 Exo）
            ├─ MediaLibrarySession + PlayerView 绑定
            ├─ EventBus 注册
            └─ Server.setService(this)
```

### 3.3 全局单例与事件

| 单例 | 作用 |
| --- | --- |
| `App` | Context、Gson、主线程 Handler、当前 Activity、可选 `Hook` PackageManager |
| `VodConfig` / `LiveConfig` / `WallConfig` | 三类配置的加载与内存态 |
| `RuleConfig` | 合并点播/直播的 `rules` 与 `ads`，按需重算 |
| `BaseLoader` | 统一调度 JAR/JS/PY 爬虫与 `/proxy` |
| `Source` | 全部媒体提取器 |
| `Server` | NanoHTTPD 与 PlaybackService 的桥 |
| `Task` | 线程池/定时器/回调适配 |

事件总线（greenrobot EventBus）：

- `ConfigEvent`：`COMMON / VOD / LIVE / WALL / BOOT`
- `RefreshEvent`：`HOME / CATEGORY / HISTORY / KEEP / SIZE / THEME / LIVE / DETAIL / PLAYER / SUBTITLE / DANMAKU / VOD`
- `ServerEvent`：`SEARCH / PUSH / SETTING`
- `CastEvent`、`ActionEvent`、`StateEvent`

### 3.4 点播主链路

```text
VideoActivity（VodActivity 只负责详情入口）
  └─ VideoViewModel.createPlaybackController(host)
       └─ VodPlaybackController
            ├─ checkId / requestDetail
            │    └─ VideoViewModel.detailContent()
            │         └─ SiteApi.detailContent(key, id)
            │              ├─ type=3 → Spider.detailContent()
            │              ├─ type=4 → HTTP call(ac=detail&ids=)
            │              └─ type=0/1 → HTTP/XML/JSON
            ├─ onDetailResult → VodDetailResult → 渲染线路/剧集/历史
            ├─ selectFlag / selectEpisode
            │    ├─ 命中预载 → 直接播放
            │    └─ 否则 dataSource.playerContent(request)
            ├─ onPlaybackResult
            │    ├─ SiteApi.playerContent(key, flag, id)
            │    ├─ Source.fetch(result)（magnet/tvbus/…）
            │    └─ host.startPlayback()
            │         └─ PlaybackActivity.startPlayer()
            │              └─ PlayerManager.parse()
            │                   ├─ 直链 → PlaySpec
            │                   └─ 需解析 → ParseJob
            └─ 播放中：进度、片头片尾、下一集、换源、画质、弹幕
```

关键类：

- `SiteApi`：所有站点调用的统一入口，区分 `type=0/1/3/4`。
- `VodPlaybackController`：把“取详情、选线路、选剧集、换源、记录、预载、错误回退”拆成显式状态机。
- `VodFallbackPolicy`：详情为空/播放失败时自动换源或搜索。
- `VodHistoryPolicy`：进度、片头片尾、反向播放、无痕。
- `VodPreloader`：下一集预取 `playerContent` + 播放器预载。
- `PlaybackActivity`（main）：公共播放壳，负责绑定 Service、MediaController、PiP、进度、弹幕视图。
- `VideoActivity`（leanback/mobile）：各自实现 `VodPlaybackHost` 与交互。

### 3.5 直播主链路

```text
LiveActivity
  └─ LiveViewModel
       ├─ LiveConfig.ensureLoaded()
       │    ├─ LiveApi.parse(live)
       │    │    ├─ LiveParser: M3U / TXT / JSON
       │    │    ├─ 分组过滤、收藏合并
       │    │    └─ EPG/XMLTV
       │    └─ Home Live 选择
       ├─ selectGroup / selectChannel
       │    └─ LivePlaybackController
       │         ├─ LiveApi.getUrl(channel) → Source.fetch()
       │         ├─ 回看：Catchup.format() + EPG 时间
       │         └─ host.startPlayback()
       └─ 频道切换、EPG 节目单、回看、密码频道
```

直播字段支持：`ua/origin/referer/header/timeZone/catchup/drm/core`，`Channel.urls` 支持多线路，TVBus 场景由 `Core` 下发 `.so` 与鉴权参数。

### 3.6 搜索

- 单站：`SiteApi.searchContent(site, keyword, quick, page)`。
- 全站：`ViewModelSearchRunner` 并发提交所有可搜索站点，逐个 `postValue`，支持取消与去重。
- 快捷搜索：`Site.quickSearch` 控制是否参与快速搜索。
- 搜索词会做繁简转换（`Trans.t2s`）。

### 3.7 播放架构

#### 引擎选择

`PlayerEngineFactory.resolve()`：

```text
if (spec.drm != null || url.scheme == "smb") → Exo
else if (PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable()) → mpv
else → Exo
```

- **Exo**：定制 `ExoPlayerEngine` + `ExoRenderersFactory`，支持 FFmpeg/AV1/Opus/Flac/IAMF/MPEG-H、libass 字幕、双字幕、章节、版本（MediaEdition）、音频/视频效果。
- **mpv**：`MpvPlayer`（定制 AAR），读取 `mpv.conf`、`gpu-next`/Vulkan、硬件解码、ASS 样式、二级字幕、磁盘缓存。
- 解码切换：`PlayerEngine.HARD/SOFT`，失败自动软解重试一次（`PlaybackSnapshot` 保留进度/倍速/音轨延迟）。
- 预加载：`PreloadSetting`，仅 Exo + 下一集 + 开启时生效。

#### 播放会话

`PlaybackService` 是 Media3 `MediaLibraryService`：

- 本地 UI 通过 `LOCAL_BIND_ACTION` 拿 `PlayerManager`（同进程直接调用）。
- 同时通过 `MediaController`/`MediaSession` 暴露标准播放控制（通知、车机、外部控制器）。
- `NavigationCallback` 把上一集/下一集/停止/循环/重播回调给当前 Activity。
- 通知栏按钮：停止、单集循环；`DefaultMediaNotificationProvider`。

#### 音视频效果

- 视频：`ExoVideoEffectController` / `MpvVideoEffectController` + GL Shader（亮度、对比度、饱和度、锐化、色温、Gamma、阴影）。
- 音频：`AudioEffectProcessor`（Exo AudioProcessor）/ mpv `af` 滤镜，支持均衡器、响度归一、对白增强、音量稳定、声道模式。
- 相关设置：`VideoSetting`、`AudioSetting`。

#### 字幕与弹幕

- 字幕：内嵌轨道、外挂文件、Assrt 在线搜索、ZIP 解压、字体导入；`SubtitleSetting` 控制样式/位置/延迟/二级字幕。
- 弹幕：`DanmakuApi` 以 `{name}`/`{episode}` 模板或 POST 拉取；`DanmakuPlayerViewController` + 定制 Media3 弹幕渲染；支持时间轴偏移、透明度、速度、字号、样式、发送。

### 3.8 爬虫三运行时

| 类型 | 触发条件 | 加载方式 | 入口类 |
| --- | --- | --- | --- |
| JAR | `api = csp_ClassName` | `DexClassLoader` 加载 `.jar`（内部含 `classes.dex`） | `com.github.catvod.spider.<ClassName>` |
| JS | `api` 含 `.js` | QuickJS 执行，注入 `http.js`/`spider.js` 与全局 Java 方法 | `__JS_SPIDER__` |
| Python | `api` 含 `.py` | Chaquopy 3.10，`SourceFileLoader` 动态加载 | `Spider` 类 |

统一抽象：`com.github.catvod.crawler.Spider`（`catvod/src/main/java/.../crawler/Spider.java`）。

三类运行时都必须实现同一组方法（可只实现需要的）：

```java
init(Context, String ext)
homeContent(boolean filter)
homeVideoContent()
categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend)
detailContent(List<String> ids)
searchContent(String key, boolean quick)
searchContent(String key, boolean quick, String pg)
playerContent(String flag, String id, List<String> vipFlags)
liveContent(String url)
manualVideoCheck()
isVideoFormat(String url)
proxy(Map<String,String> params)
action(String action)
destroy()
```

#### JAR 约定

```text
com.github.catvod.spider.Init          // 可选，静态 init(Context)
com.github.catvod.spider.Proxy         // 可选，静态 proxy(Map)
com.github.catvod.spider.<SiteApi>     // 必须是 Spider 子类，无参构造
com.github.catvod.parser.Json<Key>     // 可选，parse type=2
com.github.catvod.parser.Mix<Key>      // 可选，parse type=3
```

`jar` 支持 `URL;md5;<md5>` 形式，也支持 `md5` 指向一个返回 md5 文本的 URL。

#### JS 约定

- 资产：`quickjs/src/main/assets/js/lib/`（`http.js`、`spider.js`、`cat.js`、`cheerio.min.js`、`crypto-js.js`、`gbk.js`、`similarity.js`）。
- Spider 模块需暴露 `__JS_SPIDER__`（CatVod 风格可用 `__jsEvalReturn`）。
- 全局 Java 方法（QuickJS `Global`）：`req/_http`、`joinUrl`、`md5X`、`aesX`、`desX`、`rsaX`、`s2t/t2s`、`getProxy`、`js2Proxy`、`setTimeout/clearTimeout`。
- 本地缓存：`local.get/set/delete`。

#### Python 约定

- 基类：`chaquo/src/main/python/base/spider.py`。
- 依赖：`requirements.txt`（`lxml`、`ujson`、`pyquery`、`requests`、`cachetools`、`pycryptodome`、`beautifulsoup4`）。
- `getDependence()` 返回额外 `.py` 文件名列表，运行时会从 `api` 同目录下载。
- 基类提供 `fetch/post/html/getCache/setCache/delCache/getProxyUrl/log`。

### 3.9 解析器（Parse）

`ParseJob` 按 `Parse.type` 分派：

| type | 名称 | 行为 |
| --- | --- | --- |
| 0 | WebView | 打开 `url + 网页地址`，注入 `click`/规则脚本，嗅探媒体地址 |
| 1 | JSON API | `GET parse.url + 网页地址`，取 `url`/`data.url` |
| 2 | JAR JSON 扩展 | 调 JAR 内 `Json<Key>.parse(jxs, url)` |
| 3 | JAR Mix | 调 JAR 内 `Mix<Key>.parse(jxs, name, flag, url)` |
| 4 | Super/God | 并发尝试所有 type=1 + WebView，任一成功即返回 |

触发方式：

- 站点 `playerContent` 返回 `parse=1` 或 `url` 需要解析。
- `Result.playUrl` 前缀：
  - `json:https://...` → 临时 type=1
  - `parse:解析器名` → 指定配置中的解析器
  - 其他 → 全局当前解析器

### 3.10 本地 HTTP 服务

`Server` 在 `9978..9998` 找空闲端口并写入 `Proxy.port`，`Nano` 承载：

| 路径 | Process | 作用 |
| --- | --- | --- |
| `/`、静态资源 | `Nano.getAssets` | 内置 Web 控制台（`assets/index.html`） |
| `/action` | `Action` | 推送/搜索/设置/刷新/控制/弹幕/投屏/同步 |
| `/cache` | `Cache` | `SharedPreferences` 键值缓存（供 JS/PY 用） |
| `/file`、`/upload`、`/newFolder`、`/delFolder`、`/delFile` | `Local` | 文件浏览/上传/新建/删除（含 Range、ETag） |
| `/media` | `Media` | 当前播放状态 JSON |
| `/parse` | `Parse` | 渲染 `parse.html` 给 WebView 注入解析器 |
| `/proxy` | `Proxy` | 交给 `BaseLoader.proxy()`，支持 JAR/JS/PY 代理 |
| `/image/{key}` | `Image` | 封面/图片代理缓存 |
| `/device` | `Nano` | 设备信息 JSON |
| `/tvbus` | `Nano` | TVBus 回调响应 |

安全约束（`Local`）：

- 文件操作限制在共享存储根目录内，拒绝 `..`、`/`、`\`、NUL。
- 本机回环可访问根目录外文件（供播放器读本地路径）。
- 支持 `Range`/`ETag`/`304`，但不支持多段 Range。
- **无鉴权**：只应在可信局域网使用，不能映射公网。

### 3.11 投屏与车机

- **手机 → TV**：`mobile/dlna/DLNACastManager` 发现设备、`DLNACast` 发送 AVTransport。
- **TV 接收**：`leanback/service/DLNARendererService` + `DLNAAvTransportImpl`/`DLNARenderingControlImpl`，注册为 MediaRenderer。
- **CastActivity**：接收 `com.fongmi.android.tv.cast`，把配置/设备/历史交给播放。
- **Android Auto**：`PlaybackService` 实现 `MediaLibrarySession.Callback`，`BrowseTree` 提供根 → 点播/直播 → 历史/分组 → 频道/剧集，支持搜索与 `navigate`。

### 3.12 数据与存储

Room `AppDatabase`（`tv`，v35）：

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| `Site` | 仅持久化 `key`(PK)、`searchable`、`changeable`；其余字段 `@Ignore` | 记住用户对站点的搜索/换源开关 |
| `Live` | 仅持久化 `name`(PK)、`keep`、`boot`、`pass`；其余字段 `@Ignore` | 记住频道收藏与开机/密码状态 |
| `History` | `key`(PK)、`cid`、`vodName`、`vodPic`、`vodFlag`、`vodRemarks`、`episodeUrl`、`position`、`duration`、`speed`、`scale`、`opening`、`ending`、`revSort`、`revPlay`、`createTime` | 播放记录 |
| `Keep` | `key`(PK)、`cid`、`type`、`siteName`、`vodName`、`vodPic`、`createTime` | 收藏 |
| `Track` | `key`+`type` 唯一、`name`、`format`、`selected` | 音轨/字幕选择记忆 |
| `Config` | `id`、`type`(0/1/2)、`url`、`json`、`name`、`logo`、`home`、`parse`、`notice`、`danmaku`、`assrt` | 配置 |
| `Device` | `uuid`、`name`、`ip`、`type`、`serial`、`eth`、`wlan`、`time` | 同步设备 |

其他存储位置（`catvod/utils/Path`）：

```text
/sdcard/TV/                 用户目录（备份、字体、mpv）
/sdcard/TV/backup/          *.tv 备份（gzip JSON，最多 7 份）
/sdcard/TV/fonts/           外挂字体
/sdcard/TV/mpv/             mpv.conf
cache/jar|js|py/            爬虫缓存
files/so/                   TVBus 等下载的 .so
cache/exo|mpv/              播放缓存
```

Room 迁移：`Migrations.MIGRATION_30_31 … 34_35`，同时启用 `fallbackToDestructiveMigration(true)` 和 `allowMainThreadQueries()`。

### 3.13 网络与规则

`catvod/net/OkHttp` 统一客户端：

- **DoH**：`OkDns` + `Doh`，启动时由 `Setting.getDoh()` 注入，也可由配置的 `doh` 覆盖。
- **代理**：`OkProxySelector` 按 host 规则选择 HTTP/SOCKS 代理，`ProxyRedirectInterceptor` 处理重定向链，`OkAuthenticator` 处理 407。
- **Hosts**：自定义 DNS 覆盖。
- **Headers**：`ResponseInterceptor` 为特定 host 注入请求头。
- **Auth**：`auth` 查询参数在 host 内保持。
- **规则**：`Rule.hosts/regex/exclude/script` 用于 WebView 嗅探与脚本注入；`ads` 用于 WebView 广告域名拦截（只作用于 WebView，不影响播放器）。

### 3.14 更新与备份

- `Updater`：读取 `Github.getJson(flavor)`，比较 `code`，下载 `Github.getApk(flavor-abi)`，走 `FileProvider` 安装。
- `BackupManager`：把配置/站点/直播/记录/收藏等打包为 gzip JSON，文件名 `yyyy-MM-dd.tv`（同日覆盖），迁移旧的 `tv-*.bk.gz`，保留 7 份；`HomeActivity.onDestroy()` 触发自动备份。

---

## 4. 配置与接口契约

### 4.1 点播配置（VodConfig）

顶层 JSON（所有字段均可选，`sites` 为空时仅能使用推送/本地文件）：

```json
{
  "spider": "https://example.com/spider.jar;md5;xxxx",
  "logo": "https://example.com/logo.png",
  "notice": "公告",
  "wallpaper": "https://example.com/wall.jpg",
  "danmaku": "https://example.com/danmaku",
  "assrt": "assrt_token",
  "headers": [{"host": "example.com", "header": {"User-Agent": "..."}}],
  "proxy": [{"name": "p1", "hosts": ["a.com"], "urls": ["http://proxy:8080"]}],
  "rules": [{"name": "r1", "hosts": ["a.com"], "regex": ["m3u8"], "exclude": ["ad"], "script": ["..."]}],
  "doh": [{"name": "doh", "url": "https://dns.example/dns-query", "ips": ["1.2.3.4"]}],
  "flags": ["youku", "qq"],
  "hosts": ["example.com:1.2.3.4"],
  "ads": ["ads.example.com"],
  "sites": [],
  "parses": [],
  "lives": []
}
```

也支持：

- **Depot**：`{"urls": [{"url": "...", "name": "..."}]}`，加载第一个并删除 Depot 记录。
- **编码**：普通 JSON；含 `**` 的 Base64；以 `2423` 开头的 AES-CBC 混淆；相对 `./`/`../` JS 路径自动重写。

### 4.2 站点（Site）

| 字段 | 说明 |
| --- | --- |
| `key` | 唯一标识；与历史/收藏的 key 关联 |
| `name` | 显示名 |
| `api` | `csp_Xxx`（JAR）、`...js`、`...py`，或 HTTP 接口 |
| `type` | `0` XML、`1` JSON、`3` Spider、`4` 自定义 API（默认 0） |
| `ext` | 扩展串；以 `http` 开头会先拉取内容 |
| `jar` | 站点专用 JAR；为空则用顶层 `spider` |
| `click` | WebView 点击/解析脚本 |
| `playUrl` | 播放前拼接/解析 URL |
| `hide` | 1=隐藏 |
| `indexs` | 1=首页展示 |
| `searchable` | 1=可搜索 |
| `changeable` | 1=允许用户改线路 |
| `quickSearch` | 1=参与快捷搜索 |
| `categories` | 仅保留这些分类（按名称） |
| `header` | 请求头 |
| `style` | `type`（rect/oval/list/…) + `ratio` |
| `timeout` | 秒 |

> `Site` 同时是 Room 实体，但只持久化 `key`、`searchable`、`changeable`；每次加载配置会把数据库中的用户开关与最新配置 `sync()`，避免升级配置时丢失用户偏好。

### 4.3 解析器（Parse）

| 字段 | 说明 |
| --- | --- |
| `name` | 显示名，`parse:Name` 引用 |
| `type` | 0 WebView / 1 JSON / 2 JAR Json / 3 JAR Mix / 4 Super |
| `url` | 解析接口或网页前缀 |
| `ext.flag` | 适用 VIP flag；空=全部 |
| `ext.header` | 请求头 |
| `click` | WebView 注入脚本 |

### 4.4 Result / Vod 契约

`Result`（爬虫返回值）：

```json
{
  "class": [{"type_id": "1", "type_name": "电影"}],
  "filters": {"1": [{"key": "area", "name": "地区", "value": [{"n": "全部", "v": ""}]}]},
  "list": [Vod],
  "url": "https://.../index.m3u8",
  "header": {"Referer": "..."},
  "parse": 0,
  "jx": 0,
  "flag": "线路名",
  "playUrl": "parse:Name",
  "subs": [{"url": "...", "name": "简体", "lang": "zh", "format": "srt"}],
  "danmaku": [{"url": "...", "name": "弹幕"}],
  "drm": {"type": "..."},
  "position": 120000,
  "artwork": "https://.../poster.jpg",
  "desc": "简介",
  "format": "mp4",
  "click": "...",
  "msg": "错误信息",
  "pagecount": 20
}
```

`Vod` 兼容 CatVod 字段：`vod_id`、`vod_name`、`vod_pic`、`type_name`、`vod_remarks`、`vod_year`、`vod_area`、`vod_director`、`vod_actor`、`vod_content`、`vod_play_from`、`vod_play_url`、`vod_tag`，另有 `cate`/`style`/`land`/`circle`/`ratio`。

### 4.5 直播配置（LiveConfig）

```json
{
  "spider": "https://example.com/spider.jar",
  "lives": [
    {
      "name": "我的直播",
      "url": "https://example.com/live.m3u",
      "epg": "https://example.com/epg.xml",
      "ua": "Mozilla/5.0",
      "origin": "",
      "referer": "",
      "timeZone": "Asia/Shanghai",
      "catchup": {"type": "append", "days": "7", "regex": "...", "source": "...", "replace": "..."},
      "groups": [
        {"name": "央视", "channel": [
          {"name": "CCTV1", "urls": ["http://..."], "logo": "...", "epg": "...", "tvgId": "...", "catchup": {...}, "drm": {...}, "parse": 1}
        ]}
      ]
    }
  ]
}
```

- `lives[].url` 可直接是 M3U/TXT/JSON；也可内联 `groups`。
- TXT 使用 `#genre#` 分组；M3U 读取 `#EXTINF` 属性（`tvg-id`/`tvg-name`/`tvg-logo`/`group-title`/`catchup*`）以及 `#EXTHTTP`/`#EXTVLCOPT`/`#KODIPROP` 等头信息。
- `Core`（TVBus）：`auth/name/pass/broker/domain/so/resp/sign/pkg/key/option[]`，运行时下载 `.so` 并临时替换 `PackageManager`。

### 4.6 爬虫接口契约

| 方法 | 返回 |
| --- | --- |
| `homeContent(filter)` | `class` + `filters` + `list` |
| `homeVideoContent()` | `list` |
| `categoryContent(tid, pg, filter, extend)` | `page/pagecount/limit/total/list` |
| `detailContent(ids)` | `{list:[Vod]}`（含 `vod_play_from/vod_play_url`） |
| `searchContent(key, quick[, pg])` | `list` |
| `playerContent(flag, id, vipFlags)` | `url/parse/header/flag/jx/playUrl/subs/danmaku/drm/position/artwork/desc/format/click` |
| `liveContent(url)` | 直播 JSON 字符串（`groups`）或 M3U/TXT |
| `action(action)` | `{...}` 自定义动作 |
| `proxy(params)` | `[status, mime, stream, headers?, base64?]` |
| `isVideoFormat(url)` / `manualVideoCheck()` | 判断是否媒体地址/是否需要手动嗅探 |

### 4.7 本地 API（当前代码实际实现）

| 请求 | 参数 | 说明 |
| --- | --- | --- |
| `GET /` | `?tab=1..5` | Web 控制台（1 搜索 / 2 推送 / 3 弹幕 / 4 设置 / 5 文件） |
| `POST /action?do=push` | `url` | 推送 URL 给 App |
| `POST /action?do=search` | `word` | 调起搜索 |
| `POST /action?do=setting` | `text`,`name` | 导入配置/设置 |
| `POST /action?do=file` | `path` | 打开本地文件 |
| `POST /action?do=refresh` | `type`,`path`,`json` | 刷新 live/detail/player/category/subtitle/danmaku/vod |
| `POST /action?do=control` | `type=play|pause|stop|prev|next|repeat|replay|seek|speed|repeat_on|repeat_off`，`position`/`speed` | 播放控制；本仓库扩展 seek/speed |
| `POST /action?do=danmaku` | `text` | 发送弹幕（`[时间,模式,字号,颜色]文本`） |
| `POST /action?do=cast` | `config`,`device`,`history` | 投屏 |
| `POST /action?do=sync` | `type`,`mode`,`force`,`device`,`config`,`targets`,`configs` | 历史/收藏同步 |
| `GET /media` | — | `state/speed/duration/position/url/title/artist/artwork` |
| `GET /device` | — | `uuid/name/ip/type/serial/eth/wlan/time` |
| `GET /cache?do=get|set|del` | `key`,`rule`,`value` | 键值缓存 |
| `GET/POST /file/{path}` | `Range` | 目录 JSON 或文件下载 |
| `POST /upload` | `path` + multipart | 上传（ZIP 自动解压） |
| `GET/POST /newFolder` | `path`,`name` | 新建目录 |
| `GET/POST /delFolder|/delFile` | `path` | 递归删除 |
| `GET /parse` | `jxs`,`url` | WebView 解析页 |
| `GET /proxy` | 由爬虫决定 | JAR/JS/PY 代理 |
| `GET /image/{key}` | — | 图片代理 |
| `GET /tvbus` | — | TVBus 响应 |
| `GET /assistant/info` | — | 助手扩展：版本、设备、直播摘要、capabilities |
| `GET /assistant/live` | `group`,`keyword` | 助手扩展：直播分组/频道（不含播放 URL） |
| `POST /assistant/config` | `text`,`name`,`type` | 助手扩展：推送并加载点播/直播/壁纸配置（写入 Config 历史） |
| `GET /assistant/configs` | — | 助手扩展：三类配置历史，含当前标记 |
| `POST /assistant/config/activate` | `id` 或 `type`,`url` | 助手扩展：切换历史配置并重新加载 |
| `POST /assistant/config/delete` | `id` 或 `type`,`url` | 助手扩展：删除历史配置（当前配置拒绝） |
| `GET /assistant/sites` | — | 助手扩展：点播源列表 |
| `GET /assistant/search` | `word`,`site`,`quick` | 助手扩展：并行程搜索点播源，返回结果列表 |
| `GET /assistant/vod/detail` | `site`,`id` | 助手扩展：返回播放源和集数名称（不含 URL） |
| `POST /assistant/vod/resolve` | `site`,`id`,`flag`,`episode` | 助手扩展：解析单集 URL/headers/HLS-DASH，供手机 ExoPlayer 本地播放 |
| `POST /assistant/vod/play` | `site`,`id`,`name`,`pic`,`flag`,`episode` | 助手扩展：按所选源/集数在电视端直接起播 |
| `POST /assistant/vod/open` | `site`,`id`,`name`,`pic` | 助手扩展：在电视上打开点播详情页 |

> **文档与代码不一致提醒**：`website/app/local/page.tsx` 与 `local-api.ts` 记录了 `/remote`、`/remote/key`、`/remote/subtitle`、`/remote/danmaku`、`/remote/info`、`/upload/start|chunk|finish`、`/download/*`、持久 WebSocket、诊断分享等能力；**本快照的 `server/process` 中不存在这些实现**。这些文档应来自更新的开发分支，二开时以代码为准。

---

## 5. 二次开发指南

### 5.1 环境准备（Windows / 本机）

```powershell
# 1) JDK 21
$env:JAVA_HOME = 'D:\IDE\scoop\apps\temurin21-jdk\current'

# 2) Android SDK 37 + 对应 Build-Tools
$sdk = 'D:\Scoop\apps\android-clt\current'
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37" "build-tools;37.0.0"

# 3) Python 3.10（Chaquopy）
#    安装后在 chaquo/build.gradle 指定 buildPython，或确保 py -3.10 可用

# 4) local.properties（见 2.5）

# 5) 放入缺失的 lib-*.aar

# 6) 构建
.\gradlew.bat :app:assembleLeanbackRelease
```

首次构建会下载 Gradle 9.7.1 与 AGP/依赖，耗时较长。

### 5.2 换皮 / 改包名

最小改动（推荐）：

1. `app/build.gradle`：`applicationId`、`versionCode`、`versionName`。
2. `app/src/main/res/values*/strings.xml`：`app_name`（默认/简中/繁中）。
3. `res/mipmap-*` 与 `ic_banner`、`ic_notification`。
4. 主题色：`values/colors.xml`、`themes.xml` 及 flavor 资源。
5. 更新源：`utils/Github.java` 的 `URL`，或改成自己的 JSON 接口（字段 `code/name/desc`，APK 命名 `{flavor}-{abi}.apk`）。
6. 官网：`website/app/site-config.ts`、`layout.tsx`、Logo。

彻底改包名：

- 修改 `namespace` 与 `applicationId` 后，需要同步移动 `com/fongmi/android/tv` 包路径、所有 `import`、`R`/`BuildConfig` 引用、`AndroidManifest`、`proguard` 规则。
- 风险高，建议只改 `applicationId` 做“同代码多包”。

### 5.3 内置默认配置

App 默认无配置。要“开箱即用”：

- 方案 A：首次启动写入默认 `Config`（`Config.vod()` / `Config.live()`），或改 `VodConfig.defaultConfig()` 返回带 URL 的配置。
- 方案 B：把 JSON 放 `assets`，在 `BaseConfig.ensureLoaded()` 里先导入。
- 方案 C：用 Depot 固定一个私有配置地址。

注意：内置配置会让 App 从“空壳”变成“有内容分发”，涉及合规风险。

### 5.4 新增内容源

#### 纯 JSON/XML 源（最快）

在配置的 `sites` 中添加：

```json
{"key":"demo","name":"演示","type":1,"api":"https://api.example.com/vod","searchable":1,"quickSearch":1}
```

- `type=0`：CatVod XML 接口（`ac=videolist/detail`）。
- `type=1`：JSON 接口（`ac`、`t`、`pg`、`wd`、`f`）。
- `type=4`：自定义 JSON API（`filter`、`play`、`flag` 等参数）。

#### 新增 Spider

1. 选择运行时（JAR/JS/PY），实现 4.6 的方法。
2. 托管到可公开访问的 URL（或局域网）。
3. 配置：

```json
{"key":"demo","name":"演示","type":3,"api":"https://example.com/spider.js","ext":"{\"token\":\"x\"}","jar":"","searchable":1,"quickSearch":1}
```

4. 若为 JAR：确保 jar 内有 `classes.dex`，类名与 `csp_` 后一致；必要时提供 `Init`/`Proxy`。
5. 若为 JS：确保导出 `__JS_SPIDER__`；需要 `http`/`cheerio`/`crypto-js` 时直接 `require`/使用内置库。
6. 若为 PY：继承 `base/spider.py` 的 `Spider`，通过 `getDependence()` 声明附加模块。
7. 用 `SpiderDebug` 日志排查；确认 `siteKey` 已注入。

**调试建议**：

```bash
adb logcat -s TV home category detail search player action
```

`SiteApi` 会在 home/category/detail/search/player/action 各阶段打印入参与返回，可直接看到爬虫输出。

### 5.5 新增解析/去广告

- **新增 Parse**：在 `parses` 加条目，`type` 按 4.3 选择；用 `ext.flag` 限定 VIP 平台。
- **指定解析**：让 `playerContent` 返回 `playUrl: "parse:名称"` 或 `url: "json:接口"`。
- **网页规则**：在 `rules` 中按 host 配 `regex`/`exclude`/`script`；WebView 会注入并嗅探。
- **WebView 广告拦截**：`ads` 配 host；仅影响 WebView（`CustomWebView.isAd`）。
- **播放器广告**：本项目不做播放器内广告跳过，需要改播放器/源。

### 5.6 新增媒体提取器 / 播放引擎

#### 提取器（自定义 scheme）

```java
public class MyExtractor implements Source.Extractor {
    public boolean match(Uri uri) { return "myscheme".equals(uri.getScheme()); }
    public String fetch(String url) { /* 返回本地/HTTP 可播放地址 */ }
    public void stop() {}
    public void exit() {}
}
```

在 `Source` 构造函数注册，然后 `Source.fetch(result)` 自动调用。适合 P2P、私有协议、需本地转发的源。

#### 播放引擎

实现 `PlayerEngine`（`getPlayer`/`start`/`stop`/`setDecode`/错误处理/可选 `PlayerEffect`），在 `PlayerEngineFactory` 增加枚举与创建分支。注意：

- `PlayerManager` 依赖 Media3 `Player` 接口（章节、版本、二级字幕、弹幕等为定制 API）。
- 新引擎若不支持这些扩展，需要在这些能力上做降级。
- 若引入新 native 库，需要处理 ABI split 与 `lib-*.aar` 打包。

#### 音视频效果

- Exo：实现 `PlayerEffect` / `AudioProcessor` / GL `VideoEffect`。
- mpv：通过 `MpvPlayer.setAudioOutputListener` 或 `mpv.conf` 滤镜。
- 设置项在 `setting/VideoSetting`、`AudioSetting`，UI 在 `ui/dialog/VideoSettingPanel`、`AudioSettingPanel`。

### 5.7 新增 UI

- **手机端底部页**：`mobile/HomeActivity.initFragment()` 的 `switch(position)` + `menu_nav.xml`。
- **TV 首页行**：`leanback/HomeActivity` 的 `Func` 列表与 `FuncPresenter`。
- **新 Activity**：
  1. 在对应 flavor 的 `AndroidManifest.xml` 注册。
  2. 布局放 `app/src/{flavor}/res/layout/`，类名 `XxxActivity` 生成 `XxxBinding`。
  3. 共享逻辑放 `app/src/main`，flavor 只做 UI。
  4. 字符串同时补 `values`、`values-zh-rCN`、`values-zh-rTW`。
- **播放相关页面**：继承 `PlaybackActivity`，实现 `getPlayerView()`、`getSeekView()`、`getNavigationCallback()` 等抽象方法。

### 5.8 扩展本地 API

1. 新建 `app/src/main/java/.../server/process/Xxx.java`，实现 `server/impl/Process`：

```java
public class Xxx implements Process {
    public boolean isRequest(IHTTPSession session, String url) { return url.startsWith("/xxx"); }
    public Response doResponse(IHTTPSession session, String url, Map<String,String> files) {
        Map<String,String> p = session.getParms();
        return Nano.ok("...");
    }
}
```

2. 在 `Nano.addProcess()` 注册（顺序在静态资源兜底之前）。
3. 需要主线程/播放器状态时用 `App.post` + `CompletableFuture`（参考 `Media`）。
4. 需要鉴权时自行加 token/白名单；当前实现没有。

### 5.9 数据库变更

1. 新增/修改 `bean` 实体与 `db/dao/*Dao`。
2. 在 `AppDatabase` 注册实体并 `VERSION++`。
3. 在 `Migrations` 增加 `Migration(old, new)`，并在 `AppDatabase.create()` 注册。
4. 运行一次构建生成 `app/schemas/.../<version>.json`，提交到仓库。
5. 注意 `fallbackToDestructiveMigration(true)`：漏写迁移会**清库**。

### 5.10 修改 Web 控制台 / 官网

- 控制台：`app/src/main/assets/index.html`、`css/ui.css`、`js/script.js`，5 个 panel（搜索/推送/弹幕/设置/文件），jQuery。
- 官网：`website/`，Next.js 16 App Router，静态导出：
  ```bash
  cd website
  npm ci
  npm run lint
  npm run build   # 输出 out/
  npm start       # 本地预览
  ```
  页面：首页、功能、配置、爬虫、本地 API、播放指令。
- 修改 App 行为后，记得同步 `website/app/*.ts` 中的字段文档，避免“文档超前/滞后”。

### 5.11 调试技巧

| 场景 | 方法 |
| --- | --- |
| 全局日志 | `adb logcat -s TV`（Logger tag） |
| 爬虫入参/返回 | `adb logcat -s home category detail search player action`（`SiteApi` 以操作名为 tag 打印；`SpiderDebug.log(msg)` 用 `SpiderDebug` tag） |
| 播放器调试面板 | `PlayerSetting.putDebug(true)`，`PlaybackActivity` 自动 `toggleDebugView()` |
| WebView 解析 | 局域网浏览器打开 `http://设备IP:9978/`，或 `/parse?jxs=...&url=...` |
| 播放状态 | `GET /media`、`GET /device` |
| 推送/搜索 | `/action?do=push|search|setting` |
| 远程文件 | Web 控制台“文件”页 |
| 崩溃 | `CrashActivity`（独立 `:error_activity` 进程） |

建议二开时补充：`WebView.setWebContentsDebuggingEnabled(true)`（当前未开启），便于 Chrome DevTools 调试解析页。

### 5.12 发布与 CI

- 当前 CI 只发布官网（`.github/workflows/pages.yml`），**没有 APK 构建流水线**。
- 建议新增：
  1. `ubuntu-latest` + JDK 21 + Android SDK 37 + Python 3.10。
  2. 从私有 Release/对象存储拉取 `lib-*.aar` 与 `app/libs` 下 5 个本地 AAR。
  3. 用 GitHub Secrets 注入 keystore（base64）与密码。
  4. 构建 4 个 APK，上传 artifact / Release。
  5. 可选：跑 `:catvod:test` 与 lint。

---

## 6. 风险、限制与已知问题

### 6.1 构建/仓库

1. **上游缺失 `lib-*.aar`**：公开仓库不含这批自编译 Media3 二进制；本工作副本已通过重建 AAR + 7 个兼容类解决，功能有少量降级（见 `BUILD_REPORT.md`）。
2. **`local.properties` 未入库**：本机已创建（SDK 路径 + 临时 keystore）。
3. **SDK / Build-Tools**：本机已安装 SDK 36 与 Build-Tools 36；项目 compileSdk 37 可正常构建。
4. **Python 3.10**：已安装到 `D:\IDE\python310`，通过 `-PbuildPython` 传入。
5. **历史模块干扰**：`thunder/`、`tvbus/`、`forcetech/`、`jianpian/`、`hook/`、`zlive/` 未参与构建，容易被误改；`zlive` 完全未引用。
6. **文档超前**：官网描述的 `/remote`、断点续传、WebSocket、模块管理等在当前源码中不存在（对应更新分支）。
7. **测试薄弱**：仅 `catvod` 有一个较完整的代理重定向单测，无 UI/播放/配置测试。

### 6.2 运行时/质量

1. **信任模型**：配置可加载任意 JAR/JS/PY 并执行；等价于远程代码执行。只应使用可信配置。
2. **本地 HTTP 无鉴权**：`/action`、`/file`、`/upload` 对局域网开放；可推送、改配置、读写共享存储。不要暴露公网。
3. **`MANAGE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage` + `usesCleartextTraffic=true`**：权限与明文流量都较宽。
4. **PackageManager Hook**：TVBus 播放时全局替换 `PackageManager`，可能影响其他组件；`App` 中还可能触发 `System.exit(0)`（切换 Core 时）。
5. **Room 主线程查询**：`allowMainThreadQueries()`，数据量大时可能卡顿。
6. **破坏性迁移兜底**：升级漏迁移会清空历史/收藏。
7. **`largeHeap=true`**：配合 4K、弹幕、GL 效果，低端设备仍有 OOM 风险。
8. **R8 + 反射**：JAR/JS/PY、Media3、mpv、SimpleXML 等依赖大量 keep 规则；新增反射类要补 `proguard`。
9. **TVBus/Force/JianPian/Thunder**：均为非官方/第三方内核，ABI 限制（部分仅 `armeabi-v7a`），兼容性与合法性需自行评估。
10. **更新通道指向 FongMi**：二开换签名后，官方 APK 无法覆盖安装；必须改更新源与包名/签名策略。

### 6.3 合规

- 代码为 **GPL-3.0**：分发修改版需提供对应源码与许可声明。
- App 本身不含内容，但配置/爬虫可能指向受版权保护的内容；分发配置或内置源存在法律风险。
- 第三方 AAR（迅雷、TVBus、JianPian、ForceTech、mpv、Media3 分支）需确认各自许可与再分发权限。

---

## 7. 建议的演进路线

| 优先级 | 事项 | 收益 |
| --- | --- | --- |
| P0 | 建立私有二进制依赖仓库/Release，固化 `lib-*.aar` 与引擎 AAR | 可重复构建 |
| P0 | 增加 APK CI（含签名 secret） | 可交付 |
| P1 | 配置加载/Result 解析/播放状态机补单测 | 降低回归 |
| P1 | 本地 API 增加 token/白名单与请求日志 | 降低局域网风险 |
| P1 | 把 `server/process` 的新接口（remote/断点续传）补齐或从文档移除 | 文档一致 |
| P2 | 拆分 `PlayerManager` 与 `PlaybackActivity`（已部分拆出 playback 包） | 可维护性 |
| P2 | 统一线程模型（`Task`/`Executor`/`ListenableFuture`），减少零散线程池 | 稳定性 |
| P2 | 数据库迁移测试与 `fallbackToDestructiveMigration(false)` | 数据安全 |
| P3 | 统一 i18n、主题与设计令牌 | 品牌化 |
| P3 | 清理未使用模块（`zlive` 等）与历史目录 | 降低认知成本 |

---

## 8. 附录

### 8.1 关键类索引

| 关注点 | 文件 |
| --- | --- |
| 应用入口 | `app/src/main/java/com/fongmi/android/tv/App.java` |
| 启动初始化 | `.../Startup.java` |
| 常量/超时 | `.../Constant.java` |
| 站点调度 | `.../api/SiteApi.java` |
| 配置加载 | `.../api/config/{BaseConfig,VodConfig,LiveConfig,WallConfig,RuleConfig}.java` |
| 爬虫加载 | `.../api/loader/{BaseLoader,JarLoader,JsLoader,PyLoader}.java` |
| 配置解码 | `.../api/Decoder.java` |
| 点播状态机 | `.../playback/vod/VodPlaybackController.java` |
| 直播状态机 | `.../playback/live/LivePlaybackController.java` |
| 播放编排 | `.../player/PlayerManager.java` |
| 引擎工厂 | `.../player/engine/PlayerEngineFactory.java` |
| Exo 引擎 | `.../player/exo/ExoPlayerEngine.java`、`ExoMediaSourceFactory.java` |
| mpv 引擎 | `.../player/mpv/MpvPlayerEngine.java`、`MpvUtil.java` |
| 提取器 | `.../player/extractor/Source.java` |
| 解析任务 | `.../player/parse/ParseJob.java` |
| 播放服务 | `.../service/PlaybackService.java` |
| 本地服务 | `.../server/Server.java`、`Nano.java` |
| Web 控制台 | `app/src/main/assets/index.html`、`js/script.js` |
| 数据库 | `.../db/AppDatabase.java`、`Migrations.java` |
| 备份 | `.../db/BackupManager.java` |
| 事件 | `.../event/*.java` |
| 网络基础 | `catvod/src/main/java/com/github/catvod/net/OkHttp.java` |
| 爬虫基类 | `catvod/src/main/java/com/github/catvod/crawler/Spider.java` |
| JS 桥 | `quickjs/src/main/java/com/fongmi/quickjs/crawler/Spider.java` |
| PY 桥 | `chaquo/src/main/java/com/fongmi/chaquo/Spider.java` |

### 8.2 端口与路径

| 项 | 值 |
| --- | --- |
| 本地 HTTP | `9978–9998`（自动选空闲） |
| Web 控制台 | `http://<设备IP>:9978/` |
| 代理端点 | `http://<IP>:<port>/proxy` |
| 共享存储 | `/sdcard/TV/` |
| 备份 | `/sdcard/TV/backup/*.tv` |
| mpv 配置 | `/sdcard/TV/mpv/mpv.conf` |
| 字体 | `/sdcard/TV/fonts/` |
| 爬虫缓存 | `cache/jar`、`cache/js`、`cache/py` |
| TVBus `.so` | `files/so/` |

### 8.3 常用命令

```powershell
# 构建
$env:JAVA_HOME = 'D:\IDE\scoop\apps\temurin21-jdk\current'
.\gradlew.bat :app:assembleLeanbackRelease
.\gradlew.bat :app:assembleMobileRelease

# 单测（catvod 代理重定向）
.\gradlew.bat :catvod:test

# 安装
adb install -r Release\apk\mobile-arm64_v8a.apk

# 日志
adb logcat -s TV home category detail search player action

# 打开 Web 控制台（设备与电脑同一局域网）
start http://<设备IP>:9978/
```

### 8.4 术语

| 术语 | 含义 |
| --- | --- |
| 配置 / Config | 描述站点、解析、直播、壁纸、网络策略的 JSON |
| 站点 / Site | 一个内容源（对应 spider 或 HTTP 接口） |
| Spider | 爬虫实现（JAR/JS/PY） |
| Parse / JX | 把网页播放页解析成真实媒体地址 |
| Flag | 播放线路/VIP 平台标识（如 `qq`、`youku`） |
| Ext | 传给爬虫的扩展串（常为 JSON） |
| Core | TVBus 内核配置 |
| Catchup | 直播回看/时移规则 |
| Depot | 多配置聚合入口 |

---

> 说明：本文基于当前工作副本的源码、构建脚本、Git 历史与 `website/` 文档交叉核对。凡涉及“文档有、代码无”的能力，已在 4.7 与 6.1 标注。二次开发前建议先补齐二进制依赖并实际出包验证。
