package com.aitv.assistant

import android.content.Context

/**
 * 配对令牌的本地存储：按设备 baseUrl 保存，重启助手后自动恢复，
 * 避免每次都要重新输入电视上的配对码。
 */
object TokenStore {

    private const val PREFS = "aitv_assistant_tokens"

    fun load(context: Context): Map<String, String> {
        val all = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
        val result = LinkedHashMap<String, String>()
        for ((key, value) in all) {
            val token = value as? String
            if (!token.isNullOrEmpty()) result[key] = token
        }
        return result
    }

    fun put(context: Context, baseUrl: String, token: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(baseUrl, token)
            .apply()
    }

    fun remove(context: Context, baseUrl: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(baseUrl)
            .apply()
    }
}
