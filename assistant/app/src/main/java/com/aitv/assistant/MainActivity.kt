package com.aitv.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val DeviceRevealWidth = 88.dp
private const val REMOTE_CONFIG_URL = "https://nodeapp.uandj.site/api/ai-tv/addresses?limit=1000"
private const val REMOTE_CONFIG_REFRESH_MS = 24L * 60 * 60 * 1000

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = readInitialDevice()
        val initialTab = intent?.getIntExtra("autoTab", 0) ?: 0
        val autoSearch = intent?.getStringExtra("autoSearchWord")
        val autoDetailSite = intent?.getStringExtra("autoDetailSite")
        val autoDetailId = intent?.getStringExtra("autoDetailId")
        setContent {
            AssistantTheme {
                AssistantApp(initial, initialTab, autoSearch, autoDetailSite, autoDetailId)
            }
        }
    }

    private fun readInitialDevice(): AitvDevice? {
        val intent: Intent = intent ?: return null
        val baseUrl = intent.getStringExtra("autoBaseUrl") ?: return null
        return AitvDevice(
            intent.getStringExtra("autoUuid"),
            intent.getStringExtra("autoName"),
            baseUrl,
            intent.getIntExtra("autoType", 0),
            intent.getStringExtra("autoVersion"),
            intent.getStringExtra("autoVariant"),
            intent.getBooleanExtra("autoModern", false),
            intent.getBooleanExtra("autoAssistant", false)
        )
    }
}

@Composable
private fun AssistantApp(
    initial: AitvDevice?,
    initialTab: Int,
    autoSearch: String?,
    autoDetailSite: String?,
    autoDetailId: String?
) {
    var selected by remember { mutableStateOf(initial) }
    BackHandler(enabled = selected != null) { selected = null }
    if (selected == null) {
        DeviceScreen(onSelect = { selected = it })
    } else {
        ControlScreen(
            device = selected!!,
            onBack = { selected = null },
            initialTab = initialTab,
            initialSearchWord = autoSearch,
            initialDetailSite = autoDetailSite,
            initialDetailId = autoDetailId
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceScreen(onSelect: (AitvDevice) -> Unit) {
    val context = LocalContext.current
    val scanner = remember { LanScanner() }
    val entries = remember { mutableStateListOf<DeviceEntry>() }
    val seen = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在准备扫描…") }
    var manual by remember { mutableStateOf("") }
    var fullScan by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeviceEntry?>(null) }
    var pendingClose by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun persist() {
        DeviceStore.save(context, entries.toList())
    }

    fun loadCache() {
        if (loaded) return
        entries.clear()
        entries.addAll(DeviceStore.load(context))
        loaded = true
    }

    fun delete(entry: DeviceEntry) {
        entries.removeAll { it.device.key() == entry.device.key() }
        persist()
        val online = entries.count { it.online }
        status = if (entries.isEmpty()) {
            "缓存已清空；可重新扫描"
        } else {
            "已删除缓存：在线 $online 台，历史 ${entries.size} 台"
        }
        scope.launch { snackbar.showSnackbar("已删除缓存：${entry.device.name}") }
    }

    fun cancelDelete() {
        val close = pendingClose
        pendingDelete = null
        pendingClose = null
        close?.invoke()
    }

    fun startScan(host: String? = null) {
        if (scanning) return
        scanning = true
        fullScan = host.isNullOrBlank()
        seen.clear()
        status = if (host.isNullOrBlank()) "正在扫描局域网（9978–9987）…" else "正在探测 $host…"
        val listener = object : LanScanner.Listener {
            override fun onDevice(device: AitvDevice) {
                scope.launch(Dispatchers.Main) {
                    val key = device.key()
                    seen.add(key)
                    val now = System.currentTimeMillis()
                    val index = entries.indexOfFirst { it.device.key() == key }
                    if (index >= 0) {
                        entries[index] = DeviceEntry(device, true, now)
                    } else {
                        entries.add(DeviceEntry(device, true, now))
                    }
                    status = "发现 ${entries.count { it.online }} 台在线设备"
                }
            }

            override fun onFinished(count: Int) {
                scope.launch(Dispatchers.Main) {
                    scanning = false
                    if (fullScan) {
                        for (i in entries.indices) {
                            if (entries[i].device.key() !in seen) {
                                entries[i] = entries[i].copy(online = false)
                            }
                        }
                    }
                    persist()
                    val online = entries.count { it.online }
                    status = when {
                        entries.isEmpty() -> "未发现 AITV：请确认电视已启动、配置已加载、手机与电视在同一网段"
                        online == 0 -> "未发现在线设备；保留 ${entries.size} 条历史记录"
                        else -> "扫描完成：在线 $online 台，历史 ${entries.size} 台"
                    }
                }
            }

            override fun onError(message: String) {
                scope.launch(Dispatchers.Main) {
                    scanning = false
                    if (fullScan) {
                        for (i in entries.indices) entries[i] = entries[i].copy(online = false)
                        persist()
                    }
                    status = message
                }
            }
        }
        if (host.isNullOrBlank()) {
            scanner.scan(listener)
        } else {
            val (target, port) = parseHostPort(host)
            if (port != null) scanner.scanHost(target, port, listener) else scanner.scanHost(target, listener)
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.cancel() }
    }
    LaunchedEffect(Unit) {
        loadCache()
        startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AITV 助手", fontWeight = FontWeight.Bold)
                        Text(
                            "局域网发现 · 控制 · 点播搜索",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { startScan() }, enabled = !scanning) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            HeroHeader()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("手动 IP / IP:端口") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Wifi, contentDescription = null) }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { startScan(manual.trim()) },
                    enabled = !scanning && manual.isNotBlank()
                ) {
                    Text("探测")
                }
            }

            if (scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text("在线 ${entries.count { it.online }} / ${entries.size}")
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            if (entries.isEmpty() && !scanning) {
                EmptyState(
                    icon = Icons.Rounded.Tv,
                    title = "没有发现 AITV",
                    message = "确认电视和手机在同一 Wi-Fi；电视打开 AITV 并至少加载一次配置。也可以手动输入电视 IP 探测。",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    lazyItems(entries, key = { it.device.key() }) { entry ->
                        SwipeRevealDeviceCard(
                            entry = entry,
                            onClick = {
                                if (entry.online) onSelect(entry.device)
                                else startScan(entry.device.baseUrl)
                            },
                            onRequestDelete = { close ->
                                pendingDelete = entry
                                pendingClose = close
                            }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { cancelDelete() },
            title = { Text("删除缓存") },
            text = { Text("确定从历史记录中删除“${entry.device.name}”？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    pendingClose = null
                    delete(entry)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { cancelDelete() }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HeroHeader() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.primary, colors.tertiary, colors.secondary)
                )
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "让大屏更好用",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "发现电视后，可以直接控制播放、推送配置，并在手机上搜索点播源。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimary.copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
private fun SwipeRevealDeviceCard(
    entry: DeviceEntry,
    onClick: () -> Unit,
    onRequestDelete: (() -> Unit) -> Unit
) {
    val density = LocalDensity.current
    val revealPx = with(density) { DeviceRevealWidth.toPx() }
    val offset = remember(entry.device.key()) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch {
            offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
        Unit
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(DeviceRevealWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable { onRequestDelete(close) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除缓存",
                        tint = MaterialTheme.colorScheme.onError
                    )
                    Text(
                        "删除",
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        DeviceCard(
            entry = entry,
            onClick = onClick,
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(entry.device.key()) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (offset.value < -revealPx * 0.45f) -revealPx else 0f
                            scope.launch {
                                offset.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-revealPx, 0f))
                            }
                        }
                    )
                }
        )
    }
}

@Composable
private fun DeviceCard(entry: DeviceEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val device = entry.device
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (entry.online) colors.primaryContainer else colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (device.type == 0) Icons.Rounded.Tv else Icons.Rounded.Smartphone,
                    contentDescription = if (entry.online) "在线" else "离线",
                    tint = if (entry.online) colors.primary else colors.outline
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (device.version.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "v${device.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    device.meta(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.online && entry.lastSeen > 0) {
                    Text(
                        "最后在线：${formatLastSeen(entry.lastSeen)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.outline
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlScreen(
    device: AitvDevice,
    onBack: () -> Unit,
    initialTab: Int = 0,
    initialSearchWord: String? = null,
    initialDetailSite: String? = null,
    initialDetailId: String? = null
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var playback by remember { mutableStateOf<JSONObject?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = listOf("控制", "点播搜索", "频道", "配置")

    LaunchedEffect(device) {
        while (isActive) {
            val state = withContext(Dispatchers.IO) { AitvClient.playback(device) }
            playback = state
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            device.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (tab) {
                0 -> ControlTab(device, playback, scope, snackbar)
                1 -> SearchTab(device, scope, snackbar, initialSearchWord, initialDetailSite, initialDetailId)
                2 -> ChannelTab(device, scope, snackbar)
                else -> ConfigTab(device, scope, snackbar)
            }
        }
    }
}

@Composable
private fun ControlTab(
    device: AitvDevice,
    playback: JSONObject?,
    scope: CoroutineScope,
    snackbar: SnackbarHostState
) {
    val rawTitle = playback?.optString("title", "").orEmpty()
    val hasMedia = rawTitle.isNotBlank()
    val active = playback?.optBoolean("active", hasMedia) ?: hasMedia
    val title = rawTitle.ifBlank { "当前没有播放" }
    val position = playback?.optLong("position", 0L) ?: 0L
    val duration = playback?.optLong("duration", 0L) ?: 0L
    val state = playback?.optInt("state", 0) ?: 0
    val playing = playback?.optBoolean("playWhenReady", state == 3) ?: false
    val live = playback?.optBoolean("live", false) ?: false
    val speed = (playback?.optDouble("speed", 1.0) ?: 1.0).toFloat()
    val seekAvailable = device.modern || device.assistant
    val canSeek = seekAvailable && (playback?.optBoolean("canSeek", true) ?: true)
    var repeat by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }

    fun send(type: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AitvClient.control(device, type) }
            snackbar.showSnackbar(if (ok) "已发送：$type" else "控制失败：$type")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (live) {
                        AssistChip(
                            onClick = {},
                            label = { Text("直播") },
                            leadingIcon = {
                                Icon(Icons.Rounded.LiveTv, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
                Text(
                    if (active) {
                        "${formatTime(position)} / ${formatTime(duration)} · ${playbackStatus(state, playing)} · ${
                            String.format(Locale.US, "%.2fx", speed)
                        }"
                    } else {
                        "等待播放 · 在电视上打开视频后这里会同步状态"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val progress = if (duration > 0) position.toFloat() / duration else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(Icons.Rounded.SkipPrevious, "上一集", Modifier.weight(1f)) { send("prev") }
            ControlButton(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "暂停" else "播放",
                Modifier.weight(1f)
            ) { send(if (playing) "pause" else "play") }
            ControlButton(Icons.Rounded.Stop, "停止", Modifier.weight(1f)) { send("stop") }
            ControlButton(Icons.Rounded.SkipNext, "下一集", Modifier.weight(1f)) { send("next") }
            ControlButton(Icons.Rounded.Repeat, "循环", Modifier.weight(1f)) {
                repeat = !repeat
                send(if (seekAvailable) (if (repeat) "repeat_on" else "repeat_off") else "repeat")
            }
        }

        if (canSeek && duration > 0) {
            HorizontalDivider()
            Text("跳转", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(-30000L to "-30s", -10000L to "-10s", 10000L to "+10s", 30000L to "+30s").forEach { (delta, label) ->
                    AssistChip(
                        onClick = {
                            val target = (position + delta).coerceIn(0L, duration)
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { AitvClient.seek(device, target) }
                                snackbar.showSnackbar(if (ok) "已跳转到 ${formatTime(target)}" else "跳转失败")
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
            val max = duration.toFloat().coerceAtLeast(1f)
            Slider(
                value = (drag ?: position.toFloat()).coerceIn(0f, max),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    val target = drag
                    drag = null
                    if (target != null) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { AitvClient.seek(device, target.toLong()) }
                            snackbar.showSnackbar(if (ok) "已跳转到 ${formatTime(target.toLong())}" else "跳转失败")
                        }
                    }
                },
                valueRange = 0f..max
            )
        }

        if (seekAvailable) {
            Text("倍速", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyItems(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)) { value ->
                    FilterChip(
                        selected = abs(speed - value) < 0.01f,
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { AitvClient.speed(device, value) }
                                snackbar.showSnackbar(if (ok) "倍速 ${value}x" else "倍速设置失败")
                            }
                        },
                        label = { Text("${value}x") }
                    )
                }
            }
        } else {
            AssistChip(
                onClick = {},
                label = { Text("该设备未启用助手扩展，跳转/倍速可能不可用") },
                leadingIcon = {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SearchTab(
    device: AitvDevice,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    initialWord: String? = null,
    initialDetailSite: String? = null,
    initialDetailId: String? = null
) {
    var word by remember { mutableStateOf(initialWord ?: "") }
    var site by remember { mutableStateOf("") }
    var sites by remember { mutableStateOf<List<SiteOption>>(emptyList()) }
    var results by remember { mutableStateOf<List<VodResult>>(emptyList()) }
    var homeItems by remember { mutableStateOf<List<VodResult>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var homeRequest by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("输入片名，搜索电视上配置的点播源") }
    var detail by remember { mutableStateOf<VodDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(device) {
        val json = withContext(Dispatchers.IO) { AitvClient.sites(device) }
        sites = parseSites(json)
    }

    fun openDetail(item: VodResult) {
        if (detailLoading) return
        detailLoading = true
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.vodDetail(device, item.siteKey, item.vodId) }
            val parsed = parseVodDetail(json)
            detailLoading = false
            if (parsed == null) {
                snackbar.showSnackbar("详情加载失败：${item.vodName}")
            } else {
                detail = parsed
            }
        }
    }

    fun selectSite(key: String) {
        focusManager.clearFocus()
        keyboard?.hide()
        site = key
        results = emptyList()
        homeRequest++
        if (key.isEmpty()) {
            homeItems = emptyList()
            homeLoading = false
            message = "输入片名，搜索电视上配置的点播源"
            return
        }
        homeLoading = true
        homeItems = emptyList()
        message = "正在加载源首页推荐…"
        val request = homeRequest
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.vodHome(device, key, null) }
            if (request != homeRequest) return@launch
            val list = parseSearch(json)
            homeItems = list
            homeLoading = false
            message = when {
                json == null -> "该源暂不支持首页推荐，可直接输入片名搜索"
                list.isEmpty() -> "该源暂无首页推荐，可直接输入片名搜索"
                else -> "来自该源的推荐：${list.size} 个"
            }
        }
    }

    fun runSearch() {
        focusManager.clearFocus()
        keyboard?.hide()
        if (word.isBlank() || searching) return
        homeRequest++
        homeItems = emptyList()
        homeLoading = false
        searching = true
        message = "正在并发搜索…"
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.search(device, word.trim(), site, false) }
            val list = parseSearch(json)
            results = list
            searching = false
            message = when {
                json == null -> "搜索失败：设备离线或未启用 /assistant/search"
                list.isEmpty() -> "没有找到结果"
                else -> "找到 ${list.size} 个结果"
            }
        }
    }

    LaunchedEffect(device, initialWord) {
        if (!initialWord.isNullOrBlank()) runSearch()
    }

    LaunchedEffect(device, initialDetailSite, initialDetailId) {
        if (!initialDetailSite.isNullOrBlank() && !initialDetailId.isNullOrBlank()) {
            openDetail(
                VodResult(
                    siteKey = initialDetailSite,
                    siteName = initialDetailSite,
                    vodId = initialDetailId,
                    vodName = "",
                    vodPic = "",
                    remarks = "",
                    typeName = "",
                    year = "",
                    area = ""
                )
            )
        }
    }

    if (detail != null) {
        VodDetailScreen(
            device = device,
            detail = detail!!,
            onBack = { detail = null },
            scope = scope,
            snackbar = snackbar
        )
        return
    }

    val displayItems = if (results.isNotEmpty() || searching) results else homeItems

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = word,
            onValueChange = { word = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索片名 / 演员") },
            singleLine = true,
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { runSearch() }) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() })
        )

        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = site.isEmpty(),
                    onClick = { selectSite("") },
                    label = { Text("全部源") }
                )
            }
            lazyItems(sites, key = { it.key }) { option ->
                FilterChip(
                    selected = site == option.key,
                    onClick = { selectSite(option.key) },
                    label = { Text(option.name) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${sites.count { it.searchable }} 个可搜索源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (detailLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }

        if (homeLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }

        if (displayItems.isEmpty() && !searching && !homeLoading) {
            EmptyState(
                icon = Icons.Rounded.Movie,
                title = "搜索点播源",
                message = "输入片名后，助手会并行搜索电视上配置的所有可搜索站点，并按海报展示结果；选择某个源也可以浏览它的首页推荐。",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(displayItems, key = { it.siteKey + "|" + it.vodId }) { item ->
                    VodCard(item) { openDetail(item) }
                }
            }
        }
    }
}

@Composable
private fun VodCard(vod: VodResult, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            if (vod.vodPic.isBlank()) {
                PosterPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                )
            } else {
                AsyncImage(
                    model = vod.vodPic,
                    contentDescription = vod.vodName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(colors.surfaceVariant),
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(colors.surfaceVariant),
                    error = ColorPainter(colors.surfaceVariant)
                )
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    vod.vodName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = listOf(vod.remarks, vod.siteName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun PosterPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.surfaceVariant
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Movie,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun ChannelTab(
    device: AitvDevice,
    scope: CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var keyword by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("加载频道中…") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun loadChannels() {
        focusManager.clearFocus()
        keyboard?.hide()
        loading = true
        status = "加载频道中…"
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.live(device, keyword) }
            val list = parseChannels(json)
            groups = list
            loading = false
            status = when {
                json == null -> "该设备未启用 /assistant/live"
                list.isEmpty() -> "没有匹配频道（请先在电视上配置直播源）"
                else -> "共 ${list.sumOf { it.channels.size }} 个频道"
            }
        }
    }

    LaunchedEffect(device) { loadChannels() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("频道关键字") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.LiveTv, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { loadChannels() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "加载")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { loadChannels() })
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        if (groups.isEmpty() && !loading) {
            EmptyState(
                icon = Icons.Rounded.LiveTv,
                title = "频道检索",
                message = "电视加载直播配置后，这里会显示分组和频道；点击频道可复制频道名。",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                groups.forEach { group ->
                    item(key = "group:${group.name}") {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(group.channels.size) { index ->
                        val channel = group.channels[index]
                        ChannelRow(group.name, channel) {
                            val text = if (group.name.isBlank()) channel.name else "${group.name} / ${channel.name}"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AITV 频道", text))
                            scope.launch { snackbar.showSnackbar("已复制：$text") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(group: String, channel: ChannelItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                channel.number.ifBlank { "•" },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.isNotBlank()) {
                Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConfigTab(
    device: AitvDevice,
    scope: CoroutineScope,
    snackbar: SnackbarHostState
) {
    var type by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pushing by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf<List<ConfigEntry>>(emptyList()) }
    var loadingConfigs by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConfigEntry?>(null) }
    val types = listOf("点播", "直播", "壁纸")
    val context = LocalContext.current
    var remoteConfigs by remember { mutableStateOf<List<RemoteConfig>>(emptyList()) }
    var remoteFetchedAt by remember { mutableStateOf(0L) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteExpanded by remember { mutableStateOf(false) }
    var writingRemoteId by remember { mutableStateOf<String?>(null) }

    fun loadConfigs() {
        if (!device.assistant) return
        loadingConfigs = true
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.configs(device) }
            configs = parseConfigs(json, type)
            loadingConfigs = false
        }
    }

    fun push() {
        if (text.isBlank() || pushing) return
        pushing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AitvClient.pushConfig(device, text.trim(), name.trim(), type)
            }
            pushing = false
            snackbar.showSnackbar(if (ok) "配置已保存并加载" else "配置推送失败（已保留到历史）")
            if (ok) {
                text = ""
                name = ""
            }
            loadConfigs()
        }
    }

    fun activate(entry: ConfigEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AitvClient.activateConfig(device, entry.id) }
            snackbar.showSnackbar(if (ok) "已切换到：${entry.name.ifBlank { entry.url }}" else "切换配置失败")
            if (ok) loadConfigs()
        }
    }

    fun remove(entry: ConfigEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AitvClient.deleteConfig(device, entry.id) }
            snackbar.showSnackbar(if (ok) "配置已删除" else "删除失败")
            if (ok) loadConfigs()
        }
    }

    fun loadRemoteCache() {
        val cached = RemoteConfigStore.load(context) ?: return
        try {
            val parsed = parseRemoteConfigs(JSONObject(cached.raw))
            if (parsed.isNotEmpty()) {
                remoteConfigs = parsed
                remoteFetchedAt = cached.fetchedAt
            }
        } catch (ignored: Exception) {
        }
    }

    fun refreshRemote(force: Boolean) {
        if (remoteLoading) return
        val now = System.currentTimeMillis()
        if (!force && remoteFetchedAt > 0 && now - remoteFetchedAt < REMOTE_CONFIG_REFRESH_MS) return
        remoteLoading = true
        remoteError = null
        scope.launch {
            val json = withContext(Dispatchers.IO) { AitvClient.getJson(REMOTE_CONFIG_URL, 20000) }
            remoteLoading = false
            if (json == null) {
                remoteError = if (remoteConfigs.isEmpty()) {
                    "远程配置更新失败，请点“更新”重试"
                } else {
                    "远程配置更新失败，当前显示本地缓存"
                }
                return@launch
            }
            val list = parseRemoteConfigs(json)
            if (list.isEmpty()) {
                remoteError = "远程配置列表为空"
                return@launch
            }
            remoteConfigs = list
            remoteFetchedAt = System.currentTimeMillis()
            RemoteConfigStore.save(context, json.toString(), remoteFetchedAt)
        }
    }

    fun writeRemote(config: RemoteConfig) {
        if (!config.available || writingRemoteId != null) return
        writingRemoteId = config.id
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AitvClient.pushConfig(device, config.url, config.name, 0)
            }
            writingRemoteId = null
            snackbar.showSnackbar(if (ok) "已写入电视：${config.name}" else "写入失败：${config.name}")
            if (ok) {
                type = 0
                loadConfigs()
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRemoteCache()
        refreshRemote(false)
    }

    LaunchedEffect(device, type) { loadConfigs() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "推送会立即写入电视的配置历史并加载。之后可以在这里切换、删除，和电视上的历史配置保持一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text("当前${types[type]}配置", style = MaterialTheme.typography.titleSmall)
            val current = configs.firstOrNull { it.current }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (current != null) {
                        Text(
                            current.name.ifBlank { current.url },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            current.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            if (device.assistant) "尚未加载配置" else "该设备不支持配置历史接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "远程配置",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (remoteLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                TextButton(onClick = { refreshRemote(true) }, enabled = !remoteLoading) {
                    Text("更新")
                }
            }
            Text(
                "来源 nodeapp.uandj.site · 每天自动更新一次 · 上次更新 ${
                    if (remoteFetchedAt > 0) formatConfigTime(remoteFetchedAt) else "尚未拉取"
                }",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            remoteError?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (!remoteExpanded && remoteConfigs.isNotEmpty()) {
            item {
                val preview = remoteConfigs.filter { it.available }.ifEmpty { remoteConfigs }.take(8)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lazyItems(preview, key = { it.id }) { config ->
                        RemoteConfigCard(
                            config = config,
                            writing = writingRemoteId == config.id,
                            onWrite = { writeRemote(config) }
                        )
                    }
                }
                if (remoteConfigs.size > preview.size) {
                    TextButton(onClick = { remoteExpanded = true }) {
                        Text("查看全部 ${remoteConfigs.size} 个配置")
                    }
                }
            }
        } else if (remoteExpanded) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "远程配置 · ${remoteConfigs.size} 个",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { remoteExpanded = false }) { Text("收起") }
                }
            }
            lazyItems(remoteConfigs, key = { "remote:" + it.id }) { config ->
                RemoteConfigRow(
                    config = config,
                    writing = writingRemoteId == config.id,
                    onWrite = { writeRemote(config) }
                )
            }
        } else if (remoteLoading) {
            item {
                Text(
                    "正在拉取远程配置…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Text("推送新配置", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEachIndexed { index, label ->
                    FilterChip(
                        selected = type == index,
                        onClick = { type = index },
                        label = { Text(label) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配置 URL") },
                placeholder = { Text("https://example.com/config.json") },
                minLines = 2,
                maxLines = 3,
                singleLine = false,
                leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) }
            )
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配置名称（可选）") },
                singleLine = true
            )
        }
        item {
            Button(
                onClick = { push() },
                enabled = text.isNotBlank() && !pushing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (pushing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (pushing) "推送中…" else "推送并保存")
            }
        }

        if (!device.assistant) {
            item {
                AssistChip(
                    onClick = {},
                    label = { Text("该设备没有 /assistant/config，旧版可能需要在电视上打开配置弹窗") },
                    leadingIcon = {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已保存配置",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (loadingConfigs) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (configs.none { !it.current } && !loadingConfigs) {
            item {
                Text(
                    if (device.assistant) "还没有其他保存的配置。推送成功后会出现在这里。" else "旧版设备不支持配置历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            lazyItems(configs.filterNot { it.current }, key = { it.id }) { entry ->
                ConfigHistoryCard(
                    entry = entry,
                    typeLabel = types.getOrElse(entry.type) { "配置" },
                    onActivate = { activate(entry) },
                    onDelete = { pendingDelete = entry }
                )
            }
        }

        item {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "配置保存在电视本地；助手只负责发送、切换和删除请求。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定删除“${entry.name.ifBlank { entry.url }}”？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    remove(entry)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RemoteConfigCard(
    config: RemoteConfig,
    writing: Boolean,
    onWrite: () -> Unit
) {
    Card(modifier = Modifier.width(232.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    config.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (config.recommended) {
                    Text("推荐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "影视 ${config.siteCount} · 直播 ${config.liveCount} · 解析 ${config.parseCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                config.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!config.available) {
                Text("远程检测不可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(
                onClick = onWrite,
                enabled = config.available && !writing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (writing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (writing) "写入中…" else "一键写入电视")
            }
        }
    }
}

@Composable
private fun RemoteConfigRow(
    config: RemoteConfig,
    writing: Boolean,
    onWrite: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        config.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.recommended) {
                        Spacer(Modifier.width(6.dp))
                        Text("推荐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "影视 ${config.siteCount} · 直播 ${config.liveCount} · 解析 ${config.parseCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    config.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!config.available) {
                    Text("远程检测不可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onWrite, enabled = config.available && !writing) {
                if (writing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("写入")
                }
            }
        }
    }
}

@Composable
private fun ConfigHistoryCard(
    entry: ConfigEntry,
    typeLabel: String,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.name.ifBlank { entry.url },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entry.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$typeLabel · ${formatConfigTime(entry.time)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (entry.current) {
                    AssistChip(
                        onClick = {},
                        label = { Text("当前") },
                        leadingIcon = {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                } else {
                    TextButton(onClick = onActivate) { Text("使用") }
                }
            }
            if (!entry.current) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(20.dp)
                    .size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun parseHostPort(value: String): Pair<String, Int?> {
    var host = value.trim()
    if (host.contains("://")) host = host.substring(host.indexOf("://") + 3)
    val slash = host.indexOf('/')
    if (slash >= 0) host = host.substring(0, slash)
    val colon = host.lastIndexOf(':')
    if (colon > 0 && colon < host.length - 1) {
        return try {
            host.substring(0, colon) to host.substring(colon + 1).toInt()
        } catch (e: NumberFormatException) {
            host to null
        }
    }
    return host to null
}
