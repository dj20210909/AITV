# AITV 助手接入说明

> 目标：做一个**独立 App**，在局域网内自动发现 AITV、连接控制、推送配置、搜索内容/频道。  
> 适用：本仓库构建的 5.6.3，以及官方 5.7.x（两者接口有差异，助手需做能力探测）。

---

## 1. 发现设备

AITV 的本地 HTTP 服务由 `Server` 启动，端口从 **9978** 开始，最多尝试 **9998**：

```java
for (int i = 9978; i < 9999; i++) { ... }
```

服务没有 mDNS/UDP 广播，因此助手用 **TCP 端口扫描 + `/device` 探测**：

1. 取本机 Wi-Fi/以太网 IPv4 与 `/24` 网段（例如 `192.168.1.0/24`）。
2. 并发连接 `IP:9978..9987`（预设 10 个端口；AITV 理论可用到 9998，超出时需扩展）。
3. **端口开放不等于发现设备**：连上后还会 `GET http://IP:PORT/device`，必须返回带 `uuid`/`name` 的 AITV 设备 JSON；其他服务占用该端口会被忽略。
4. 再请求 `/remote/info` 或 `/assistant/info` 判断新版远程接口/助手扩展；两项都失败但 `/device` 成功的仍按旧版 AITV 保留。

`/device` 返回：

```json
{
  "uuid": "android-id",
  "name": "客厅电视",
  "ip": "http://192.168.1.23:9978",
  "type": 0,
  "serial": "",
  "eth": "",
  "wlan": "aa:bb:cc:dd:ee:ff",
  "time": 1788825600000
}
```

`type`：`0` = TV（leanback），`1` = 手机（mobile）。  
以 `uuid` 去重；`ip` 字段已经是完整 base URL。

4. 能力探测：

```http
GET /remote/info
```

- `200` 且返回 `version` → **5.7.x 新接口**（支持 `/remote`、`/remote/key`、`/remote/info`）。
- `404`/失败 → **旧接口**（使用 `/action`、`/media`）。
- 无论新旧，`GET /device` 都可用。

> 扫描并发建议 64–128，连接超时 150–250ms，HTTP 探测超时 800ms；只扫本网段，避免跨路由。

---

## 2. 旧版（5.6.3）控制接口

所有接口都是 `http://IP:PORT`，默认无鉴权、无 HTTPS。

### 2.1 播放控制

```http
POST /action?do=control
Content-Type: application/x-www-form-urlencoded

type=play|pause|stop|prev|next|repeat|replay|seek|speed|repeat_on|repeat_off
position=120000      # seek，毫秒
speed=1.5            # speed，0.1..4.0
```

- 原始 5.6.3 只支持 `play/pause/stop/prev/next/repeat/replay`。
- 本仓库扩展的 `Action.onControl` 新增 `seek`、`speed`、`repeat_on`、`repeat_off`；助手在旧版设备上也能快进/跳转和调倍速。
- `seek`/`speed` 会通过 `PlaybackService.player()` 的 `seekTo` / `setSpeed` 应用到当前播放器。
- 助手控制页提供 `-30s / -10s / +10s / +30s` 快捷跳转、可拖动进度条和 `0.75x–3.0x` 倍速 Chip。

### 2.2 播放状态

```http
GET /media
```

返回：

```json
{
  "state": 3,
  "speed": 1.0,
  "duration": 7200000,
  "position": 120000,
  "url": "https://...",
  "title": "标题",
  "artist": "副标题",
  "artwork": "https://.../poster.jpg"
}
```

`state`：`1` 结束 / `2` 就绪未播 / `3` 播放中 / `6` 缓冲。

### 2.3 推送配置

```http
POST /action?do=setting

text=https://example.com/config.json
name=我的配置        # 可选
```

`text` 也可以是本地路径或配置文本；App 会走 `ServerEvent.SETTING` 打开配置流程。

### 2.4 推送媒体 URL

```http
POST /action?do=push

url=https://example.com/video.m3u8
```

### 2.5 搜索（点播）

```http
POST /action?do=search

word= keywords
```

App 会打开搜索界面并跨站点搜索。

### 2.6 刷新/弹幕/投屏/同步

```http
POST /action?do=refresh&type=live|detail|player|category|subtitle|danmaku|vod
POST /action?do=danmaku&text=...
POST /action?do=cast&config=...&device=...&history=...
POST /action?do=sync&type=history|keep&mode=0|1|2&...
```

### 2.7 文件/缓存/代理

```http
GET  /file/{path}
POST /upload
GET  /cache?do=get|set|del&key=...&value=...
GET  /proxy?...
```

> 这些接口没有鉴权，助手不要暴露到公网；只应在可信局域网使用。

---

## 3. 新版（5.7.x）远程接口

### 3.1 播放状态/控制

```http
GET  /remote
POST /remote

type=play|pause|stop|prev|next|repeat_on|repeat_off|seek|speed
position=120000      # seek，毫秒
speed=1.5            # speed，0.1..4.0
```

返回播放状态 JSON：

```json
{
  "active": true,
  "title": "标题",
  "mediaKey": "md5(url)",
  "artwork": "/remote/artwork?key=md5(artworkUrl)",
  "position": 120000,
  "duration": 7200000,
  "state": 3,
  "playWhenReady": true,
  "repeatOne": false,
  "live": false,
  "canNavigate": true,
  "canSeek": true,
  "canSetSpeed": true,
  "speed": 1.0,
  "engine": "ExoPlayer",
  "video": "1920 x 1080"
}
```

### 3.2 设备信息

```http
GET /remote/info
```

```json
{
  "version": "5.7.8",
  "versionCode": 578,
  "variant": "leanback",
  "buildType": "release",
  "device": "TV",
  "android": "12",
  "sdk": 31,
  "abis": "arm64-v8a, armeabi-v7a"
}
```

### 3.3 遥控按键（可用于打开直播/切台）

```http
POST /remote/key

key=up|down|left|right|ok|back|menu|0..9
action=press|down|repeat|up|cancel
id=<1..64 位字母数字/->   # down/repeat/up/cancel 必填
```

返回 `{"accepted":true,"holding":false}`。  
`press` 是一次完整按键；长按用 `down` → 多次 `repeat` → `up/cancel`。

### 3.4 字幕/弹幕

```http
POST /remote/subtitle   key=<mediaKey>&path=/TV/a.ass
POST /remote/danmaku    key=<mediaKey>&path=https://.../danmaku.xml
```

`mediaKey` 必须等于当前播放 URL 的 MD5；路径必须通过 App 的文件校验。

### 3.5 封面

```http
GET /remote/artwork?key=<md5(artworkUrl)>
```

返回 PNG。

### 3.6 同源限制

`/remote` 的 POST 会检查 `Origin`：

- 没有 `Origin` 头（原生 App/curl 默认）→ 允许。
- 有 `Origin` 且与 `Host` 不同 → `403`。

因此 Android 助手直接发 `application/x-www-form-urlencoded` POST，不要伪造跨域 `Origin`。

---

## 4. 搜台/频道

### 4.1 现状

5.6.3 和 5.7.x **都没有**“返回直播分组/频道列表”或“按频道名选台”的 HTTP 接口。  
现成能力只有：

- `/action?do=search&word=`：点播搜索，不是频道。
- `/remote/key`：模拟遥控器按键，可以打开直播页、上下切台、数字键切台，但依赖当前 UI 状态，不适合做稳定的“搜台”。

### 4.2 本仓库新增的 `/assistant` 扩展（推荐）

为了让助手稳定拿到频道并支持“搜台”，在 AITV 中新增只读接口：

```http
GET /assistant/info
```

```json
{
  "name": "AITV",
  "version": "5.6.3",
  "versionCode": 563,
  "variant": "leanback",
  "device": "客厅电视",
  "capabilities": ["control", "config", "configs", "configActivate", "configDelete", "search", "live", "sites", "vodSearch", "vodDetail", "vodPlay", "vodOpen"],
  "live": {
    "name": "我的直播",
    "groups": 8,
    "channels": 326
  }
}
```

```http
GET /assistant/live?group=央视&keyword=CCTV
```

```json
{
  "name": "我的直播",
  "groups": [
    {
      "name": "央视",
      "channels": [
        {
          "name": "CCTV1",
          "number": "1",
          "logo": "https://.../cctv1.png",
          "epg": "https://.../epg.xml"
        }
      ]
    }
  ]
}
```

- 不返回真实播放 URL、Header、DRM、Cookie，避免把源信息暴露给助手。
- `group`/`keyword` 都是可选过滤；结果上限 5000 条。
- 接口只读取内存中已经加载的直播配置，不主动触发网络加载，避免配置地址不可达时请求被 30 秒网络超时阻塞；电视启动/打开直播页时会自动加载。

### 4.2.1 推送配置（可自动生效）

```http
POST /assistant/config
Content-Type: application/x-www-form-urlencoded

text=https://example.com/config.json
name=我的配置          # 可选
type=0                 # 0 点播 / 1 直播 / 2 壁纸
```

- 直接在 App 内执行 `VodConfig.load` / `LiveConfig.load` / `WallConfig.load`，不依赖“配置对话框已打开”。
- **推送会立即写入电视的 Config 历史表**（同一 URL+type 去重），然后加载；加载成功后会更新时间，失败也会保留记录，方便在助手或电视设置里删除/重试。
- 等待加载完成（最多 20 秒），成功返回 `OK`，失败返回错误文本。
- 只接受 URL；`text` 建议是 http(s) 配置文件地址。
- 这是一项会立即改变 App 内容的操作，助手应二次确认。

### 4.2.1.1 配置历史管理

```http
GET /assistant/configs
```

返回点播/直播/壁纸三类配置历史，按 `time` 倒序，并标记当前配置：

```json
{
  "vod": {
    "current": { "id": 4, "type": 0, "name": "饭太硬 fty 镜像", "url": "https://.../fty.json", "time": 1792558156388, "current": true },
    "items": [
      { "id": 4, "type": 0, "name": "饭太硬 fty 镜像", "url": "https://.../fty.json", "time": 1792558156388, "current": true },
      { "id": 1, "type": 0, "name": "测试点播配置", "url": "http://.../config.json", "time": 1792553035757, "current": false }
    ]
  },
  "live": { "current": { "...": "..." }, "items": [] },
  "wall": { "current": { "...": "..." }, "items": [] }
}
```

```http
POST /assistant/config/activate
Content-Type: application/x-www-form-urlencoded

id=4
```

- 从历史中按 `id` 重新加载配置；成功后该条目的 `time` 更新并成为当前配置。
- 也支持 `type` + `url` 定位（兼容没有 id 的旧记录）。

```http
POST /assistant/config/delete
Content-Type: application/x-www-form-urlencoded

id=1
```

- 删除历史配置；当前正在使用的配置会被拒绝删除，避免 App 处于“配置已删但仍在内存中”的状态。
- 与电视设置里的历史配置使用同一张 Room `Config` 表，助手和电视看到的是同一份历史。

### 4.2.2 点播源列表 / 搜索 / 详情 / 播放

```http
GET /assistant/sites
```

```json
{
  "home": "test",
  "sites": [
    { "key": "test", "name": "测试点播源", "searchable": true, "quickSearch": true, "hide": false }
  ]
}
```

```http
GET /assistant/search?word=测试&site=test&quick=0
```

```json
{
  "word": "测试",
  "count": 3,
  "sites": [
    { "key": "test", "name": "测试点播源", "count": 3 }
  ],
  "items": [
    {
      "siteKey": "test",
      "siteName": "测试点播源",
      "vodId": "1",
      "vodName": "测试电影 One",
      "vodPic": "http://192.168.99.122:8001/poster.png",
      "vodRemarks": "HD 中字",
      "typeName": "电影",
      "vodYear": "2026",
      "vodArea": "测试"
    }
  ]
}
```

- `word` 必填；`site` 可选，空表示所有可搜索站点；`quick=1` 只搜索启用快速搜索的站点。
- AITV 内用 8 线程并行调用 `SiteApi.searchContent`，最多等待 25 秒；每个站点最多 60 条、总数最多 300 条，按 `siteKey + vodId` 去重。
- 搜索只返回列表字段，不返回播放地址；助手点击结果后调用详情接口，在手机端选集/选源。

```http
GET /assistant/vod/detail?site=test&id=1
```

```json
{
  "siteKey": "test",
  "siteName": "测试点播源",
  "vodId": "1",
  "vodName": "测试电影 One",
  "vodPic": "http://.../poster.png",
  "vodRemarks": "HD 中字",
  "typeName": "电影",
  "vodYear": "2026",
  "vodArea": "测试",
  "content": "剧情简介……",
  "flags": [
    {
      "flag": "test",
      "show": "测试线路",
      "episodes": [
        { "name": "第1集", "desc": "" },
        { "name": "第2集", "desc": "" }
      ]
    }
  ]
}
```

- 返回播放源（`flag/show`）和集数名称，**不返回剧集播放 URL**。
- 详情由电视端 `SiteApi.detailContent` 加载；同一源可能需要几十秒，助手应显示加载状态。

```http
POST /assistant/vod/resolve
Content-Type: application/x-www-form-urlencoded

site=test&id=1&flag=test&episode=第1集
```

```json
{
  "url": "http://192.168.99.137:9978/proxy?do=m3u8&url=...",
  "title": "测试电影 One",
  "episode": "第1集",
  "flag": "test",
  "format": "hls",
  "parse": false,
  "headers": { "Referer": "https://example.com/" }
}
```

- 电视端会重新 `detailContent` + `playerContent`，返回**仅选中集**的可播放地址，不返回整个剧集列表的 URL。
- 如果播放地址是 `127.0.0.1`/`localhost` 的 AITV 代理，服务端会改写为电视的局域网 IP，手机才能访问。
- `format` 会标记 `hls`/`dash`，助手用对应 ExoPlayer MediaSource；`parse=true` 时表示需要电视端解析，助手会提示改用电视播放。

```http
POST /assistant/vod/play
Content-Type: application/x-www-form-urlencoded

site=test&id=1&name=测试电影&pic=http://.../poster.png&flag=test&episode=第1集
```

- 电视端会重新校验 `flag + episode`，写入该内容的 History（flag/集数/时间），再启动 `VideoActivity`。
- 启动时通过 `mark=episode` 让 `VideoActivity` 恢复对应集数；因此打开后直接是所选播放源和集数，而不是默认第一集。
- 返回 `OK` 只代表播放意图已发出；源本身需要登录/会员/失效时仍可能播放失败。
- Android 10+ 后台启动 Activity 有限制：AITV 在前台时最可靠。

```http
POST /assistant/vod/open
Content-Type: application/x-www-form-urlencoded

site=test&id=1&name=测试电影&pic=http://.../poster.png
```

- 兼容旧行为：只打开电视端详情页，由电视端选择线路/集数。
- 返回 `OK` 只代表意图已成功发出，不代表视频一定能播放（取决于源是否可用）。
- 该接口需要设备支持 `/assistant/vod/open`；助手会先检查 `/assistant/info` 的 `capabilities`。


> 这是本仓库的扩展，不是官方 5.7.8 的标准接口。助手应先用 `/assistant/info` 判断是否支持；不支持时显示“该设备不支持频道列表”，仍保留旧版控制/搜索。

### 4.3 频道播放（后续扩展点）

`/assistant/live` 只做检索。若需要“点频道直接播放”，建议再加：

```http
POST /assistant/live/play
group=央视&channel=CCTV1
```

实现方式：在 `LiveActivity` 增加 `assistant_group` / `assistant_channel` intent extra，启动后在分组/频道适配器加载完成时选中对应项。  
注意：leanback 与 mobile 是两个 `LiveActivity`，需要同时改；当前版本先不做，避免影响播放稳定性。

---

## 5. 助手 App 架构

```text
┌──────────────────────────────┐
│ UI（Kotlin + Jetpack Compose）│
│  DeviceScreen                │
│  ControlScreen               │
│   ├─ 控制                    │
│   ├─ 点播搜索（海报网格）     │
│   ├─ 频道                    │
│   └─ 配置                    │
├──────────────────────────────┤
│ Material 3 + Coil 3          │
│  - 动态取色 / 深浅色主题      │
│  - LazyVerticalGrid 海报     │
│  - Snackbar / 进度反馈       │
├──────────────────────────────┤
│ AitvClient (HTTP, Java)      │
│  - Legacy: /action /media    │
│  - Modern: /remote /remote/* │
│  - Assistant: /assistant/*   │
├──────────────────────────────┤
│ Android                      │
│  HttpURLConnection / org.json│
│  Coroutines（IO / Main）      │
└──────────────────────────────┘
```

### 5.1 扫描流程

```text
1. 读取本机 IPv4（Wi-Fi/以太网）→ 取 /24 网段
2. 线程池 96：对每个 host 尝试 9978..9987（预设 10 个端口）
3. TCP 连接成功 → GET /device（900ms）
4. 必须解析出 uuid/name，其他端口服务忽略
5. GET /remote/info 或 /assistant/info → version/capabilities
6. 按 uuid/baseUrl 去重；发现的设备写入本地缓存
7. 扫描结束后，未发现的缓存项标记为“离线”，保留最后在线时间
```

### 5.2 连接/控制流程

```text
选中设备
  → GET /remote/info（判断新旧）
  → 每 2s GET /remote 或 /media（刷新状态）
  → 控制：POST /remote 或 /action?do=control
  → 配置：POST /assistant/config（写入历史并加载；旧版降级 /action?do=setting）
  → 配置历史：GET /assistant/configs
  → 切换历史配置：POST /assistant/config/activate
  → 删除历史配置：POST /assistant/config/delete
  → 点播源：GET /assistant/sites
  → 搜索：GET /assistant/search?word=&site=
  → 详情/选集/选源：GET /assistant/vod/detail?site=&id=
  → 本机解析：POST /assistant/vod/resolve（返回单集 URL/headers/format）
  → 本机 ExoPlayer 播放：Media3 ExoPlayer + PlayerView（HLS/DASH/MP4）
  → 直接电视播放：POST /assistant/vod/play（site/id/flag/episode）
  → 仅打开电视详情：POST /assistant/vod/open
  → 频道：GET /assistant/live
```

### 5.3 数据模型

```kotlin
// AitvDevice / PlaybackState 仍由 Java AitvClient 提供
data class AitvDevice(
    val uuid: String,
    val name: String,
    val baseUrl: String,   // http://ip:port
    val type: Int,         // 0 TV, 1 phone
    val version: String,
    val variant: String,
    val modern: Boolean,   // /remote available
    val assistant: Boolean // /assistant available
)

data class SiteOption(
    val key: String,
    val name: String,
    val searchable: Boolean,
    val quickSearch: Boolean
)

data class VodResult(
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val vodName: String,
    val vodPic: String,
    val remarks: String,
    val typeName: String,
    val year: String,
    val area: String
)

data class ChannelItem(
    val name: String,
    val number: String,
    val logo: String,
    val epg: String
)

data class ChannelGroup(
    val name: String,
    val channels: List<ChannelItem>
)

data class VodEpisode(
    val name: String,
    val desc: String
)

data class VodFlag(
    val flag: String,
    val show: String,
    val episodes: List<VodEpisode>
)

data class VodDetail(
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val vodName: String,
    val vodPic: String,
    val remarks: String,
    val typeName: String,
    val year: String,
    val area: String,
    val content: String,
    val flags: List<VodFlag>
)
```

---

## 6. 安全与兼容

1. **局域网信任模型**：接口无鉴权，助手只应在可信 Wi-Fi 使用；不要做公网端口映射。
2. **HTTP 明文**：AITV 使用 `http://`，助手需要 `usesCleartextTraffic=true`。
3. **同源限制**：原生 App 不要发送 `Origin` 头。
4. **新旧兼容**：
   - 先 `/remote/info`；失败则按 legacy 处理。
   - `/assistant/*` 仅本仓库扩展；官方 App 返回 404，助手降级。
5. **端口不固定**：AITV 从 9978 开始找空闲端口，理论上可用到 9998；助手当前预设扫描 **9978–9987 十个端口**，实际端口超出该窗口时需要扩展。
6. **设备可能休眠**：扫描失败时给出“电视未启动/服务未启动/不在同一网段”的提示。
7. **配置推送会改变 App 首页内容**：助手应二次确认，并保留原配置 URL。

---

## 7. 独立助手 App

已在 `assistant/` 创建独立 Gradle 工程（不加入 AITV 的 `settings.gradle`）：

- 包名：`com.aitv.assistant`
- 功能：扫描、设备缓存、状态、播放控制（快退/快进/跳转/倍速）、推送配置、配置历史管理、点播源搜索、点播详情（播放源/集数选择）、**本机 ExoPlayer 播放**、频道检索
- 设备页：发现结果写入本地缓存；卡片用图标颜色区分在线/离线（在线为高亮色、离线为灰色，不再显示 leanback/旧接口/扩展等技术字段），离线卡片显示最后在线时间并可点击重新探测；左滑后显示红色“删除”按钮，点按钮才弹出确认框，确认后才移除缓存。
- 点播搜索结果点击后进入手机端详情页：海报、简介、播放源 Chip、集数网格；底部两个悬浮按钮：
  - **本机播放**：调用 `/assistant/vod/resolve`，用 Media3 ExoPlayer 在手机本地播放（HLS/DASH/MP4，支持内置控制条、进度拖动和倍速）
  - **电视播放**：调用 `/assistant/vod/play`，按所选源和集数让电视直接起播
- 本机播放器：`androidx.media3:media3-exoplayer/ui` 1.8.0 + HLS/DASH 模块；`PlayerView` 通过 `AndroidView` 嵌入 Compose，并放在独立的 `LocalPlayerActivity` 中全屏播放（避免叠在助手 Tab 里）。
- UI：**Kotlin + Jetpack Compose + Material 3**（动态取色、深浅色主题、卡片、FilterChip、LazyVerticalGrid）
- 图片：Coil 3（`coil-compose` + `coil-network-okhttp`）
- 最低 Android 7.0（API 24），targetSdk 37
- 网络：Java `AitvClient`（`HttpURLConnection` + `org.json`），Compose 侧用协程 `Dispatchers.IO`
- 构建：

```powershell
cd assistant
$env:JAVA_HOME = 'D:\IDE\scoop\apps\temurin21-jdk\current'
$env:ANDROID_HOME = 'D:\Scoop\apps\android-clt\current'
gradle --init-script ..\tmp\mirror.init.gradle :app:assembleDebug
```

> `assistant/` 是独立工程，AITV 的 `settings.gradle` 不 include 它；两者可以分别安装、分别升级。

产物：

```text
assistant/app/build/outputs/apk/debug/app-debug.apk   # 约 24.5 MB（含 Compose/Coil/Media3）
Release/assistant/aitv-assistant-debug.apk            # 同一 APK 的发布副本
```

---

## 8. 本机实测结果

| 项 | 结果 |
| --- | --- |
| AITV 5.6.3 Release | 已安装并启动，`http://192.168.99.137:9978/device` 返回 200 |
| `/assistant/info` | 200，返回版本 5.6.3、leanback、capabilities |
| `/assistant/live` | 200；测试直播 2 组 4 频道；饭太硬直播配置 6 组 141 频道 |
| `/assistant/config` | 200 `OK`，推送测试直播配置成功；推送后写入配置历史 |
| `/assistant/configs` | 200，返回 VOD / 直播 / 壁纸三类历史，并标记当前配置 |
| `/assistant/config/activate` | 200 `OK`，激活 id=8 后 `/assistant/live` 返回 6 组 141 频道 |
| `/assistant/config/delete` | 200 `OK`，删除 id=7；尝试删除当前配置被拒绝 |
| 助手配置历史 UI | 当前配置卡片 + “已保存配置”列表，非当前项提供“使用/删除”，当前项显示“当前”徽标 |
| `/action?do=control` | 200；`type=pause` 返回 `OK`；`type=seek position=3000` 后 `/media` position=3000；`type=speed speed=1.5` 后 `/media` speed=1.5 |
| 助手控制 UI | 旧版 5.6.3 + 助手扩展也显示 `-30s/-10s/+10s/+30s`、跳转滑块和“倍速”Chip；选择 1.5x 后电视实际生效 |
| `/media` | 200，空闲时返回 `{}` |
| 助手扫描 | 扫描本机 `/24`，发现 **2 台**设备：`192.168.99.137`（5.6.3 + 助手扩展）、`192.168.99.112`（旧版 AITV，无扩展） |
| 设备缓存/在线标识 | 扫描结果持久化；重启后缓存仍在；停掉一台后图标变灰并显示“最后在线”；状态栏显示“在线 1 / 2” |
| 左滑删除 | 左滑先露出删除按钮；点击“删除”按钮才弹出确认框，再点确认移除，点“取消”卡片恢复 |
| 助手 UI | Compose Material 3：设备页渐变 Hero、设备卡片；控制页 4 个 Tab、播放卡片、滑块/倍速、点播搜索海报网格、频道列表、配置页 |
| 助手点播搜索 | 测试源：`/assistant/search?word=测试` 返回 3 条；真实源：`word=庆余年` 返回 **131 条 / 32 个可搜索源**，海报正常加载 |
| 搜索键盘 | 点播搜索和频道搜索触发后自动 `clearFocus` + 收起软键盘 |
| 饭太硬原地址 | ❌ `http://www.饭太硬.com/tv` 当前域名已失效/停放，AITV 推送返回 500；`fantaiying.com` 首页显示“Is for sale” |
| 饭太硬可用镜像 | ✅ `https://qist.wyfc.qzz.io/fty.json`（`qist/tvbox` 的 fty 配置），`/assistant/sites` 返回 49 个站点、32 个可搜索源 |
| 饭太硬直播 | ✅ `http://193.123.86.190:14888/TV/iptv.php`，`/assistant/live` 返回 6 组 141 个频道 |
| `/assistant/vod/open` | 200 `OK`，电视端 `VideoActivity` 成功回到前台（AITV 在前台时） |
| `/assistant/vod/detail` | 200；`光影 / 35200` 返回 1 个播放源、26 集；`AiDj` 返回 1 个源、122 集 |
| `/assistant/vod/resolve` | 200；Bili 返回 DASH 代理 URL + headers；多个 HLS 源返回 `format=hls` 的单集 URL |
| 本机 ExoPlayer | ✅ 独立 `LocalPlayerActivity` 全屏；直接 MP4 正常渲染画面；HLS 代理能加载视频帧；PlayerView 控制条 + 倍速 Chip |
| `/assistant/vod/play` | 200 `OK`；选择 `光影 / wsym3u8 / 第01集` 后 `/media` 显示播放中（position>0） |
| 助手点播详情 UI | 海报、简介、播放源 Chip、集数网格、底部“本机播放/电视播放”双悬浮按钮 |
| AITV Release 包 | `Release/apk/` 下 leanback/mobile × arm64/armeabi 共 4 个 Release APK，包含最新 `/assistant` 接口 |

设备名/版本来自 `/device` 和 `/remote/info` / `/assistant/info`；测试直播配置由本机 `python -m http.server 8000` 提供，AITV 成功拉取并通过 `/assistant/live` 暴露频道。

> 说明：本机 MIUI 限制了 `adb shell input tap`，因此没有在 UI 上逐一点击“播放/推送/频道”按钮；HTTP 控制已用 `curl` 验证。助手 App 的按钮走同一套 `AitvClient` 方法。

### 8.1 AITV 侧改动

- 新增 `app/src/main/java/com/fongmi/android/tv/server/process/Assistant.java`
- `Nano.addProcess()` 注册 `Assistant`
- 新增 `/assistant/info`、`/assistant/live`、`/assistant/config`
- 扩展 `Action.onControl`：新增 `seek`、`speed`、`repeat_on`、`repeat_off`，旧版 5.6.3 也能跳转/快进和调倍速
- 新增 `/assistant/sites`、`/assistant/search`、`/assistant/vod/detail`、`/assistant/vod/resolve`、`/assistant/vod/play`、`/assistant/vod/open`
- 新增 `/assistant/configs`、`/assistant/config/activate`、`/assistant/config/delete`，与电视设置共用 Room `Config` 历史表
- `/assistant/config` 推送时立即写入历史，再执行加载
- `/assistant/info` 增加 `capabilities`，助手据此判断是否支持点播搜索/打开结果
- `leanback/HomeActivity` 对 `DLNARendererService.start()` 做异常保护（Android 12+ 在后台/dozing 时可能抛 `BackgroundServiceStartNotAllowedException`，此前会导致启动崩溃）

新增接口只读（`/assistant/config`、`/assistant/config/activate`、`/assistant/config/delete`、`/assistant/vod/play`、`/assistant/vod/open` 除外），不返回播放 URL、Header、Cookie、DRM。若需要“点频道直接播放”，按 4.3 增加 `/assistant/live/play` 和 LiveActivity 选中逻辑。
