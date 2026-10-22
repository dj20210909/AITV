package com.aitv.assistant

import android.content.Context

data class CachedRemoteConfigs(
    val raw: String,
    val fetchedAt: Long
)

object RemoteConfigStore {

    private const val PREFS = "aitv_remote_configs"
    private const val KEY_RAW = "raw"
    private const val KEY_TIME = "fetched_at"

    fun load(context: Context): CachedRemoteConfigs? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RAW, null) ?: return null
        return CachedRemoteConfigs(raw, prefs.getLong(KEY_TIME, 0L))
    }

    fun save(context: Context, raw: String, fetchedAt: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RAW, raw)
            .putLong(KEY_TIME, fetchedAt)
            .apply()
    }
}
