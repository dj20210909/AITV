# 编译报告（AITV / 影視TV 5.6.3）

> 日期：2026-10-21  
> 目标：`D:\codes\AITV`，`leanback` / `mobile` 双端 Release  
> 结论：**编译成功，4 个 Release APK 已产出并完成 v2 签名校验。**

---

## 1. 构建产物

| 产物 | 大小 | 路径 |
| --- | --- | --- |
| TV / arm64-v8a | 91.5 MB | `Release/apk/leanback-arm64_v8a.apk` |
| TV / armeabi-v7a | 78.4 MB | `Release/apk/leanback-armeabi_v7a.apk` |
| 手机 / arm64-v8a | 91.4 MB | `Release/apk/mobile-arm64_v8a.apk` |
| 手机 / armeabi-v7a | 78.3 MB | `Release/apk/mobile-armeabi_v7a.apk` |

APK 信息（`aapt dump badging`）：

```text
package: com.fongmi.android.tv
versionCode: 563
versionName: 5.6.3
compileSdk: 37
```

签名（`apksigner verify`）：

```text
Verified using v2 scheme: true
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
```

> ⚠️ 当前使用临时测试 keystore `tmp/build-test.jks`（密码 `android`）。正式分发前请替换为自己的签名，否则无法覆盖安装既有版本。

---

## 2. 构建命令

```powershell
$env:JAVA_HOME = 'D:\IDE\scoop\apps\temurin21-jdk\current'
$env:ANDROID_HOME = 'D:\Scoop\apps\android-clt\current'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PIP_INDEX_URL = 'https://pypi.tuna.tsinghua.edu.cn/simple'

# 项目 pin 了 JetBrains JDK 21；本机是 Temurin 21，构建时先移走该文件
Move-Item gradle\gradle-daemon-jvm.properties tmp\gradle-daemon-jvm.properties.bak

gradle --init-script tmp\mirror.init.gradle `
  :app:assembleLeanbackRelease `
  --project-prop buildPython=D:/IDE/python310/python.exe

gradle --init-script tmp\mirror.init.gradle `
  :app:assembleMobileRelease `
  --project-prop buildPython=D:/IDE/python310/python.exe
```

Debug 版对应 `assembleLeanbackDebug` / `assembleMobileDebug`，产物在 `app/build/outputs/apk/`。

助手 App 是独立工程（Kotlin + Compose）：

```powershell
cd assistant
gradle --init-script ..\tmp\mirror.init.gradle :app:assembleDebug
```

产物：`assistant/app/build/outputs/apk/debug/app-debug.apk`。

---

## 3. 为编译做的环境准备

| 项 | 处理 |
| --- | --- |
| JDK | `D:\IDE\scoop\apps\temurin21-jdk\current`（JDK 21） |
| Android SDK | 已安装 `platforms;android-36`、`build-tools;36.0.0` |
| Python 3.10 | 已安装到 `D:\IDE\python310`（Python 3.10.11） |
| Gradle | 使用本机 Gradle 9.8.2；另下载 Gradle 9.1.0 用于构建 FongMi/media AAR |
| Maven Central | 本机 DNS/TLS 被污染，使用 `tmp/mirror.init.gradle` 走腾讯云镜像 |
| JitPack | Gradle 下 TLS 握手失败，`NewPipeExtractor` / LibRtmp 放入本地 `tmp/m2` |
| Chaquopy | `chaquo/build.gradle` 增加可选 `buildPython` 属性，构建时通过 `-P` 传入 |
| 签名 | 临时 keystore `tmp/build-test.jks` |

`chaquo/build.gradle` 的改动：

```groovy
python {
    version = "3.10"
    if (project.hasProperty("buildPython")) {
        buildPython = [project.property("buildPython").toString()]
    }
    pip { install("-r", "requirements.txt") }
}
```

---

## 4. Media3 AAR 的来源

`app/libs/` 中已放入 **21 个 AAR**（均被 `app/libs/.gitignore` 的 `lib-*.aar` 忽略，不会污染 Git）：

- 19 个从公开 `FongMi/media` 源码构建：
  `lib-common`、`lib-container`、`lib-datasource*`、`lib-decoder`、`lib-decoder-ffmpeg`、`lib-effect`、`lib-exoplayer*`、`lib-extractor`、`lib-mpvplayer`、`lib-session`、`lib-ui`、`lib-ui-danmaku`、`lib-ui-leanback`。
- 1 个从 `FongMi/media` GitHub Actions 产物取得：
  `lib-exoplayer-libass-release.aar`（含 `libmedia3ass.so`，arm64-v8a / armeabi-v7a）。
- 1 个官方 `androidx.media3:media3-database:1.8.0` 的 AAR，补 `StandaloneDatabaseProvider`。

这些 AAR 与 5.6.3 源码有少量 API 版本差（见第 5 节），已通过兼容层解决。

---

## 5. 兼容层（本次新增/修改的源码）

公开 `FongMi/media` 快照比 App 5.6.3 少 7 个类，且 libass/mpv 的二级字幕 API 较旧。为编译与基础功能，新增了以下兼容类：

```text
app/src/main/java/androidx/media3/exoplayer/libass/LibassConfiguration.java
app/src/main/java/androidx/media3/exoplayer/libass/LibassSubtitleController.java
app/src/main/java/androidx/media3/exoplayer/libass/LibassFontFile.java
app/src/main/java/androidx/media3/exoplayer/text/SecondaryTextOutput.java
app/src/main/java/androidx/media3/exoplayer/trackselection/SecondaryTextTrackSelector.java
app/src/main/java/androidx/media3/ui/libass/LibassPlayerViewController.java
app/src/main/java/androidx/media3/ui/danmaku/DanmakuPlayerViewController.java
```

同时修改了 5 个现有文件以适配旧版 API：

| 文件 | 改动 |
| --- | --- |
| `ExoPlayerSession.java` | `LibassPlaybackSession` 改用旧版 `(fontConfig, fontsDir, fontFamily, enabled)` 构造 |
| `ExoSubtitleController.java` | 去掉旧版不存在的 libass 位置/缩放 setter |
| `MpvPlayerEngine.java` | 二级字幕状态返回 `EMPTY`，去掉旧版不存在的 mpv 二级轨道 API |
| `MpvUtil.java` | 去掉旧版 `MpvSubtitleOptions.Builder` 不支持的二级/字体 setter |
| `ExternalFont.java` | `LibassFontFile` 兼容实现不抛 `IOException`，调整 catch |
| `chaquo/build.gradle` | 支持 `-PbuildPython` |

### 功能影响

- **代码路径保留（未真机验证）**：Exo 播放、mpv 播放、主字幕、libass 基础 ASS/SSA 渲染、弹幕显示/发送、视频/音频效果、预加载、片头片尾、直播、DLNA、本地 HTTP。
- **降级**：二级字幕（双字幕）在 libass/mpv 路径下为空实现；libass 的字体/位置/缩放高级覆盖不可用；弹幕使用旧版 `DanmakuView` 封装。
- 若要恢复完整功能，需要作者那份与 5.6.3 完全匹配的 `lib-exoplayer-libass` / `lib-mpvplayer` / `lib-ui-danmaku` AAR，替换 `app/libs/` 后即可去掉大部分兼容代码。

---

## 5.1 AITV 助手扩展

为独立助手 App 新增接口：

```text
app/src/main/java/com/fongmi/android/tv/server/process/Assistant.java
```

- `GET /assistant/info`：版本、设备、直播配置摘要、能力列表（`sites`/`vodSearch`/`vodDetail`/`vodPlay`/`vodOpen`/`configs`/`configActivate`/`configDelete`）。
- `GET /assistant/live?group=&keyword=`：直播分组/频道（名称、编号、台标、EPG），不返回播放 URL/Header/Cookie/DRM。
- `POST /assistant/config`：`text`（配置 URL）、`name`、`type`（0 点播 / 1 直播 / 2 壁纸），**立即写入 Config 历史**，再执行 `VodConfig/LiveConfig/WallConfig.load` 并等待结果。
- `GET /assistant/configs`：返回三类配置历史（id/name/url/time/current），与电视设置共用同一张 Room `Config` 表。
- `POST /assistant/config/activate`：按 `id`（或 `type+url`）切换历史配置并重新加载。
- `POST /assistant/config/delete`：删除历史配置；当前配置拒绝删除。
- `GET /assistant/sites`：电视上已配置的点播源列表（key/name/searchable/quickSearch）。
- `GET /assistant/search?word=&site=&quick=`：并行搜索点播源，返回站点统计和 `siteKey/vodId/vodName/vodPic/vodRemarks/typeName` 等列表字段。
- `GET /assistant/vod/detail?site=&id=`：返回播放源（flag/show）和集数名称，不返回剧集 URL。
- `POST /assistant/vod/resolve`：`site/id/flag/episode`，解析单集播放地址、headers、HLS/DASH 标记；把 `127.0.0.1` 代理地址改写为电视局域网 IP，供手机本机播放。
- `POST /assistant/vod/play`：`site/id/name/pic/flag/episode`，校验所选源和集数，写入 History 后启动 `VideoActivity`，按所选集数起播。
- `POST /assistant/vod/open`：`site/id/name/pic`，通过 Intent 在电视上启动 `VideoActivity` 打开详情页。
- 已在 `Nano.addProcess()` 注册。
- `/assistant/info` 和 `/assistant/live` 不再主动 `ensureLoaded()`，避免直播配置地址不可达时把只读请求阻塞 30 秒。

同时修复 leanback `HomeActivity`：`DLNARendererService.start()` 在 Android 12+ 后台/dozing 状态会抛 `BackgroundServiceStartNotAllowedException`，已加 try/catch，避免启动崩溃。

助手 App 位于独立工程 `assistant/`，UI 为 **Kotlin + Jetpack Compose + Material 3**，图片加载用 Coil 3；说明见 `ASSISTANT_INTEGRATION.md`。Debug APK：

```text
assistant/app/build/outputs/apk/debug/app-debug.apk   # 约 24.5 MB
```

已复制到 `Release/assistant/aitv-assistant-debug.apk`，方便直接安装。

---

## 6. 验证情况

| 项 | 结果 |
| --- | --- |
| `:app:compileLeanbackDebugJavaWithJavac` | ✅ |
| `:app:assembleLeanbackDebug` | ✅ |
| `:app:assembleLeanbackRelease` | ✅ |
| `:app:assembleMobileRelease` | ✅ |
| `apksigner verify` | ✅ v2 签名通过 |
| `aapt dump badging` | ✅ 包名/版本 5.6.3 |
| 真机安装（root） | ✅ 已安装并启动 Release 版 |
| `/device`、`/assistant/info` | ✅ 200，返回设备/版本，`info` 不再因直播配置不可达而超时 |
| `/assistant/config` | ✅ 200 `OK`，推送后写入 Config 历史并加载 |
| `/assistant/configs` | ✅ 200，返回 VOD / 直播 / 壁纸三类历史，含当前标记 |
| `/assistant/config/activate` | ✅ 200 `OK`，激活 id=8 后直播 6 组 141 频道 |
| `/assistant/config/delete` | ✅ 200 `OK`，删除 id=7；删除当前配置被拒绝 |
| `/assistant/live` | ✅ 200；测试直播 2 组 4 频道；饭太硬直播配置 6 组 141 频道 |
| `/assistant/sites` | ✅ 200；测试源 1 个；饭太硬镜像返回 49 个站点、32 个可搜索源 |
| 饭太硬直播 | ✅ `http://193.123.86.190:14888/TV/iptv.php`，6 组 141 个频道 |
| `/assistant/search?word=测试` | ✅ 200，`count=3`，返回 3 条结果和站点统计 |
| `/assistant/search?word=庆余年` | ✅ 200，`count=131`，32 个源返回结果 |
| 饭太硬原始地址 | ❌ `http://www.饭太硬.com/tv` 域名已失效/停放，推送返回 500 |
| `/assistant/vod/open` | ✅ 200 `OK`，AITV 前台时电视端 `VideoActivity` 成功回到前台 |
| `/assistant/vod/detail` | ✅ 200，返回播放源和集数（如 `光影` 1 源 26 集、`AiDj` 1 源 122 集） |
| `/assistant/vod/resolve` | ✅ 200，返回单集 URL/headers/format；HLS 源标记 `hls`，Bili 返回 DASH |
| 助手本机 ExoPlayer | ✅ Media3 ExoPlayer + PlayerView 独立全屏 Activity；MP4/HLS 能渲染画面，内置控制条和倍速 Chip |
| `/assistant/vod/play` | ✅ 200 `OK`；`光影 / 第01集` 起播后 `/media` 显示 state=3、position>0 |
| 助手点播详情 UI | ✅ 海报/简介/播放源/集数网格 + “在电视播放”悬浮按钮 |
| `/action?do=control` | ✅ 200；`pause` OK；`seek=3000` 后 `/media` position=3000；`speed=1.5` 后 `/media` speed=1.5 |
| 助手控制 UI | ✅ 旧版 5.6.3 也显示 `±10s/±30s`、跳转滑块和倍速 Chip，实测电视端生效 |
| 助手 App 扫描 | ✅ 实测发现 2 台 AITV（含本机 5.6.3） |
| 设备缓存/在线 | ✅ 设备持久化；离线卡片显示“离线/最后在线”；状态栏显示在线 1/2 |
| 左滑删除缓存 | ✅ 左滑先露出“删除”按钮；点按钮弹确认框，点确认移除并持久化，点取消卡片恢复 |
| 助手 Compose UI | ✅ 设备页、控制页、点播搜索页、频道页、配置页均可打开 |
| 助手配置历史 UI | ✅ 当前配置卡片、已保存配置列表、使用/删除操作、当前徽标 |
| 远程配置列表 | ✅ 拉取 93 个 TVBox 配置，显示推荐/统计/URL，缓存到本地 |
| 远程配置缓存 | ✅ 24 小时内复用缓存；手动“更新”强制刷新 |
| 一键写入电视 | ✅ 点击后写入 AITV 当前配置并刷新配置历史 |
| 助手点播搜索 UI | ✅ 测试源显示 3 张卡片；饭太硬镜像搜索“庆余年”显示 131 个结果和真实海报 |
| 搜索键盘 | ✅ 点播搜索/频道搜索触发后自动 clearFocus 并收起软键盘 |

因此**编译、打包、安装、启动、HTTP 接口、真实点播源搜索均已验证**；播放内容本身未逐一验证每个源的清晰度/可用性，`/media` 在空闲时返回 `{}`。安装时设备 MIUI 会拦截普通 `adb install`，本机使用 root `pm install` 完成。

---

## 7. 复现步骤

1. 确认 `app/libs/` 中有 21 个 `lib-*.aar`（本机已放入；它们不在 Git 中）。
2. 确认 `local.properties` 存在，且 `tmp/build-test.jks` 或你自己的 keystore 路径正确。
3. 移走 `gradle/gradle-daemon-jvm.properties`（或安装 JetBrains JDK 21）。
4. 执行第 2 节的 Gradle 命令。

如果换一台机器，还需要：

- 安装 Python 3.10；
- 安装 Android SDK 36 / Build-Tools；
- 准备可访问 Maven Central 的网络，或复用 `tmp/mirror.init.gradle`；
- 重新放入 `app/libs/lib-*.aar`（可以在本机 `D:\codes\AITV\app\libs` 直接拷贝）。

---

## 8. 当前仓库状态

```text
已修改（源码，建议提交）：
  app/src/main/java/.../ExoPlayerSession.java
  app/src/main/java/.../ExoSubtitleController.java
  app/src/main/java/.../MpvPlayerEngine.java
  app/src/main/java/.../MpvUtil.java
  app/src/main/java/.../ExternalFont.java
  app/src/main/java/.../server/Nano.java
  app/src/main/java/.../server/process/Action.java
  chaquo/build.gradle

新增（源码，建议提交）：
  app/src/main/java/androidx/media3/** 下的 7 个兼容类
  app/src/main/java/.../server/process/Assistant.java
  assistant/**（独立 Compose 工程）
  ASSISTANT_INTEGRATION.md
  BUILD_REPORT.md
  PROJECT_OVERVIEW.md

未跟踪（本机构建资产，不提交）：
  app/libs/lib-*.aar（21 个，已被 .gitignore 忽略）
  local.properties
  tmp/（日志、镜像脚本、本地 m2、临时 keystore、Media3 AAR 备份、测试配置）

产物（不提交）：
  Release/apk/*.apk
  app/build/**
  assistant/app/build/**
```
