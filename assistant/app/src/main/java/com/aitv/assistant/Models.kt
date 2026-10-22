package com.aitv.assistant

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

data class ConfigEntry(
    val id: Int,
    val type: Int,
    val name: String,
    val url: String,
    val time: Long,
    val current: Boolean
)

data class RemoteConfig(
    val id: String,
    val name: String,
    val url: String,
    val source: String,
    val recommended: Boolean,
    val available: Boolean,
    val siteCount: Int,
    val liveCount: Int,
    val parseCount: Int,
    val group: String
)

data class DeviceEntry(
    val device: AitvDevice,
    val online: Boolean,
    val lastSeen: Long
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

data class ResolvedMedia(
    val url: String,
    val title: String,
    val episode: String,
    val flag: String,
    val format: String,
    val parse: Boolean,
    val headers: Map<String, String>
)

fun parseResolvedMedia(json: JSONObject?): ResolvedMedia? {
    if (json == null) return null
    val url = json.optString("url")
    if (url.isEmpty()) return null
    val headers = LinkedHashMap<String, String>()
    val headerObject = json.optJSONObject("headers")
    if (headerObject != null) {
        for (key in headerObject.keys()) {
            val value = headerObject.optString(key)
            if (value.isNotEmpty()) headers[key] = value
        }
    }
    return ResolvedMedia(
        url = url,
        title = json.optString("title", "本机播放"),
        episode = json.optString("episode"),
        flag = json.optString("flag"),
        format = json.optString("format"),
        parse = json.optBoolean("parse", false),
        headers = headers
    )
}

fun parseVodDetail(json: JSONObject?): VodDetail? {
    if (json == null) return null
    val vodId = json.optString("vodId")
    if (vodId.isEmpty()) return null
    val flagsArray = json.optJSONArray("flags") ?: return null
    val flags = ArrayList<VodFlag>(flagsArray.length())
    for (i in 0 until flagsArray.length()) {
        val flagObject = flagsArray.optJSONObject(i) ?: continue
        val flag = flagObject.optString("flag")
        if (flag.isEmpty()) continue
        val episodesArray = flagObject.optJSONArray("episodes") ?: continue
        val episodes = ArrayList<VodEpisode>(episodesArray.length())
        for (j in 0 until episodesArray.length()) {
            val episode = episodesArray.optJSONObject(j) ?: continue
            val name = episode.optString("name")
            if (name.isEmpty()) continue
            episodes.add(VodEpisode(name, episode.optString("desc")))
        }
        if (episodes.isNotEmpty()) {
            flags.add(
                VodFlag(
                    flag = flag,
                    show = flagObject.optString("show", flag).ifBlank { flag },
                    episodes = episodes
                )
            )
        }
    }
    if (flags.isEmpty()) return null
    return VodDetail(
        siteKey = json.optString("siteKey"),
        siteName = json.optString("siteName", json.optString("siteKey")),
        vodId = vodId,
        vodName = json.optString("vodName", "未命名"),
        vodPic = json.optString("vodPic"),
        remarks = json.optString("vodRemarks"),
        typeName = json.optString("typeName"),
        year = json.optString("vodYear"),
        area = json.optString("vodArea"),
        content = json.optString("content"),
        flags = flags
    )
}

fun parseConfigs(json: JSONObject?, type: Int): List<ConfigEntry> {
    val key = when (type) {
        1 -> "live"
        2 -> "wall"
        else -> "vod"
    }
    val section = json?.optJSONObject(key) ?: return emptyList()
    val array = section.optJSONArray("items") ?: return emptyList()
    val result = ArrayList<ConfigEntry>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optInt("id", -1)
        val url = item.optString("url")
        if (id <= 0 || url.isEmpty()) continue
        result.add(
            ConfigEntry(
                id = id,
                type = item.optInt("type", type),
                name = item.optString("name"),
                url = url,
                time = item.optLong("time", 0L),
                current = item.optBoolean("current", false)
            )
        )
    }
    return result
}

fun parseSites(json: JSONObject?): List<SiteOption> {
    val array = json?.optJSONArray("sites") ?: return emptyList()
    val result = ArrayList<SiteOption>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val key = item.optString("key")
        if (key.isEmpty()) continue
        result.add(
            SiteOption(
                key = key,
                name = item.optString("name", key),
                searchable = item.optBoolean("searchable", true),
                quickSearch = item.optBoolean("quickSearch", false)
            )
        )
    }
    return result
}

fun parseSearch(json: JSONObject?): List<VodResult> {
    val array = json?.optJSONArray("items") ?: return emptyList()
    val result = ArrayList<VodResult>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("vodId")
        if (id.isEmpty()) continue
        result.add(
            VodResult(
                siteKey = item.optString("siteKey"),
                siteName = item.optString("siteName", item.optString("siteKey")),
                vodId = id,
                vodName = item.optString("vodName", "未命名"),
                vodPic = item.optString("vodPic"),
                remarks = item.optString("vodRemarks"),
                typeName = item.optString("typeName"),
                year = item.optString("vodYear"),
                area = item.optString("vodArea")
            )
        )
    }
    return result
}

fun parseChannels(json: JSONObject?): List<ChannelGroup> {
    val array = json?.optJSONArray("groups") ?: return emptyList()
    val result = ArrayList<ChannelGroup>(array.length())
    for (i in 0 until array.length()) {
        val group = array.optJSONObject(i) ?: continue
        val channelsArray = group.optJSONArray("channels") ?: continue
        val channels = ArrayList<ChannelItem>(channelsArray.length())
        for (j in 0 until channelsArray.length()) {
            val channel = channelsArray.optJSONObject(j) ?: continue
            channels.add(
                ChannelItem(
                    name = channel.optString("name"),
                    number = channel.optString("number"),
                    logo = channel.optString("logo"),
                    epg = channel.optString("epg")
                )
            )
        }
        if (channels.isNotEmpty()) result.add(ChannelGroup(group.optString("name"), channels))
    }
    return result
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun playbackStatus(state: Int, playing: Boolean): String = when {
    state == 6 -> "缓冲中"
    state == 3 || playing -> "播放中"
    state == 2 -> "已就绪"
    state == 1 -> "已结束"
    else -> "空闲"
}

fun formatConfigTime(value: Long): String {
    if (value <= 0) return "未记录"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
}

fun formatLastSeen(value: Long): String {
    if (value <= 0) return "未知"
    val diff = (System.currentTimeMillis() - value).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
    }
}

fun parseRemoteConfigs(json: JSONObject?): List<RemoteConfig> {
    val array = json?.optJSONArray("addresses") ?: return emptyList()
    val result = ArrayList<RemoteConfig>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val url = item.optString("url")
        if (url.isEmpty()) continue
        val name = item.optString("name").ifBlank { item.optString("id", url) }
        val aliases = ArrayList<String>()
        val aliasesArray = item.optJSONArray("aliases")
        if (aliasesArray != null) {
            for (j in 0 until aliasesArray.length()) {
                val alias = aliasesArray.optString(j)
                if (alias.isNotEmpty()) aliases.add(alias)
            }
        }
        result.add(
            RemoteConfig(
                id = item.optString("id", url),
                name = name,
                url = url,
                source = item.optString("source"),
                recommended = item.optBoolean("recommended", false),
                available = item.optBoolean("available", true),
                siteCount = item.optInt("siteCount", 0),
                liveCount = item.optInt("liveCount", 0),
                parseCount = item.optInt("parseCount", 0),
                group = item.optString("group")
            )
        )
    }
    return result
}
