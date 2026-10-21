package com.aitv.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DeviceStore {

    private const val PREFS = "aitv_assistant_devices"
    private const val KEY = "entries"

    fun load(context: Context): List<DeviceEntry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<DeviceEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val baseUrl = item.optString("baseUrl")
                if (baseUrl.isEmpty()) continue
                val device = AitvDevice(
                    item.optString("uuid"),
                    item.optString("name", baseUrl),
                    baseUrl,
                    item.optInt("type", 0),
                    item.optString("version"),
                    item.optString("variant"),
                    item.optBoolean("modern", false),
                    item.optBoolean("assistant", false)
                )
                result.add(
                    DeviceEntry(
                        device = device,
                        online = false,
                        lastSeen = item.optLong("lastSeen", 0L)
                    )
                )
            }
            result
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, entries: List<DeviceEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            val device = entry.device
            val item = JSONObject()
            item.put("uuid", device.uuid ?: "")
            item.put("name", device.name ?: "")
            item.put("baseUrl", device.baseUrl)
            item.put("type", device.type)
            item.put("version", device.version ?: "")
            item.put("variant", device.variant ?: "")
            item.put("modern", device.modern)
            item.put("assistant", device.assistant)
            item.put("lastSeen", entry.lastSeen)
            array.put(item)
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
