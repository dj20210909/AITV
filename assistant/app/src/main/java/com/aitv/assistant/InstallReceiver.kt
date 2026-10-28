package com.aitv.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * 接收 PackageInstaller 的安装结果。
 *
 * 非系统应用提交会话后拿到的是 STATUS_PENDING_USER_ACTION，
 * 必须主动拉起 EXTRA_INTENT 里的确认界面，否则什么都不会发生。
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        android.util.Log.i("UpdateManager", "install result status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    Toast.makeText(context, "无法打开安装确认界面", Toast.LENGTH_LONG).show()
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    android.util.Log.e("UpdateManager", "start confirm failed", e)
                    Toast.makeText(context, "无法打开安装确认界面", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知错误"
                android.util.Log.w("UpdateManager", "install failed: $message")
                Toast.makeText(context, "安装失败：$message", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
