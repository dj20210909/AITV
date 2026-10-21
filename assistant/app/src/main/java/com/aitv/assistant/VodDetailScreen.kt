package com.aitv.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VodDetailScreen(
    device: AitvDevice,
    detail: VodDetail,
    onBack: () -> Unit,
    scope: CoroutineScope,
    snackbar: SnackbarHostState
) {
    var flagIndex by remember(detail) { mutableIntStateOf(0) }
    var episodeIndex by remember(detail, flagIndex) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val flag = detail.flags.getOrNull(flagIndex) ?: detail.flags.first()
    val episodes = flag.episodes

    BackHandler { onBack() }

    fun playLocal() {
        val episode = episodes.getOrNull(episodeIndex) ?: return
        if (resolving) return
        resolving = true
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                AitvClient.resolveVod(
                    device,
                    detail.siteKey,
                    detail.vodId,
                    flag.flag,
                    episode.name
                )
            }
            val media = parseResolvedMedia(json)
            resolving = false
            when {
                media == null -> snackbar.showSnackbar("本机播放解析失败")
                media.parse -> snackbar.showSnackbar("该源需要解析，暂不支持本机播放，请用电视播放")
                else -> {
                    val intent = android.content.Intent(context, LocalPlayerActivity::class.java).apply {
                        putExtra("url", media.url)
                        putExtra("title", media.title)
                        putExtra("episode", media.episode)
                        putExtra("flag", media.flag)
                        putExtra("format", media.format)
                        putExtra("headers", HashMap(media.headers))
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    fun play() {
        val episode = episodes.getOrNull(episodeIndex) ?: return
        if (playing) return
        playing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AitvClient.playVod(
                    device,
                    detail.siteKey,
                    detail.vodId,
                    detail.vodName,
                    detail.vodPic,
                    flag.flag,
                    episode.name
                )
            }
            playing = false
            snackbar.showSnackbar(if (ok) "电视开始播放：${episode.name}" else "播放失败")
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(78.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 170.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DetailHeader(detail = detail, onBack = onBack)
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("播放源", style = MaterialTheme.typography.titleSmall)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.flags.size) { index ->
                        val item = detail.flags[index]
                        FilterChip(
                            selected = flagIndex == index,
                            onClick = {
                                flagIndex = index
                                episodeIndex = 0
                            },
                            label = { Text(item.show, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "选集",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${episodes.size} 集",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (episodes.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "该播放源没有返回剧集",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(episodes.size) { index ->
                    FilterChip(
                        selected = episodeIndex == index,
                        onClick = { episodeIndex = index },
                        label = {
                            Text(
                                episodes[index].name,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ExtendedFloatingActionButton(
                onClick = { playLocal() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Rounded.Smartphone, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (resolving) "解析中…" else "本机播放")
            }
            ExtendedFloatingActionButton(
                onClick = { play() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                if (playing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    Icon(Icons.Rounded.Tv, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "发送中…" else "电视播放")
            }
        }
    }
}

@Composable
private fun DetailHeader(detail: VodDetail, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text("返回", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        detail.siteName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (detail.vodPic.isBlank()) {
                PosterPlaceholder(
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                AsyncImage(
                    model = detail.vodPic,
                    contentDescription = detail.vodName,
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    detail.vodName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOf(detail.remarks, detail.typeName, detail.year, detail.area)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "电视端将按所选播放源和集数直接起播",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (detail.content.isNotBlank()) {
            Text(
                detail.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(2.dp))
    }
}
