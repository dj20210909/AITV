package com.aitv.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 助手的自动更新：读取应用商店的 update.json，下载并拉起系统安装器。
 *
 * 与服务端约定（其它字段忽略）：
 * { "versionCode": 3, "versionName": "0.2.1", "updateContent": "...", "url": "...", "size": 1234, "force": false }
 */
object UpdateManager {

    private const val ENDPOINT = "https://nodeapp.uandj.site/api/file-sharing/app-store/aitv-assistant/update.json"
    private const val USER_AGENT = "AITVAssistant/0.1"
    private const val BUFFER = 64 * 1024

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val content: String,
        val url: String,
        val size: Long,
        val force: Boolean,
    )

    fun currentVersionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    fun currentVersionName(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 阻塞请求，请在 IO 线程调用；没有新版本或网络失败返回 null。 */
    fun check(context: Context): Info? {
        return try {
            val json = JSONObject(httpGet(ENDPOINT, 8000))
            val code = json.optInt("versionCode", 0)
            if (code <= currentVersionCode(context)) return null
            val url = normalize(json.optString("url"))
            if (url.isEmpty()) return null
            Info(
                versionCode = code,
                versionName = json.optString("versionName"),
                content = json.optString("updateContent"),
                url = url,
                size = json.optLong("size"),
                force = json.optBoolean("force"),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 下载到应用私有目录，阻塞，请在 IO 线程调用。失败返回 null。 */
    fun download(context: Context, info: Info, onProgress: (Int) -> Unit): File? {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "update").apply { mkdirs() }
        val target = File(dir, "aitv-assistant.apk")
        val temp = File(dir, "aitv-assistant.apk.tmp")
        return try {
            val connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            if (connection.responseCode !in 200..299) return null
            val total = if (info.size > 0) info.size else connection.contentLength.toLong()
            var read = 0L
            val buffer = ByteArray(BUFFER)
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        output.write(buffer, 0, count)
                        if (total > 0) onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            connection.disconnect()
            // 服务端给的 size 是可用的完整性信号：对不上就不装
            if (info.size > 0 && read != info.size) {
                android.util.Log.w("UpdateManager", "size mismatch: expected=" + info.size + " got=" + read)
                temp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "download failed", e)
            e.printStackTrace()
            temp.delete()
            null
        }
    }

    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 拉起系统安装器（PackageInstaller 会话，MIUI/EMUI 上比 ACTION_VIEW 可靠）。 */
    fun install(context: Context, file: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("aitv-assistant.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "install failed", e)
            e.printStackTrace()
            false
        }
    }

    /** 服务端经反代后可能给出 http://host:443/...，这里纠正成 https。 */
    private fun normalize(url: String): String {
        if (!url.startsWith("http://")) return url
        val start = "http://".length
        val slash = url.indexOf('/', start)
        val authority = if (slash < 0) url.substring(start) else url.substring(start, slash)
        val rest = if (slash < 0) "" else url.substring(slash)
        return if (authority.endsWith(":443")) "https://" + authority.dropLast(4) + rest else url
    }

    private fun httpGet(url: String, timeout: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
