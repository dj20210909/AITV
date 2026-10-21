package com.aitv.assistant

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class LocalPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val headers = intent.getSerializableExtra("headers") as? HashMap<String, String> ?: HashMap()
        val media = ResolvedMedia(
            url = url,
            title = intent.getStringExtra("title").orEmpty().ifBlank { "本机播放" },
            episode = intent.getStringExtra("episode").orEmpty(),
            flag = intent.getStringExtra("flag").orEmpty(),
            format = intent.getStringExtra("format").orEmpty(),
            parse = false,
            headers = headers
        )
        setContent {
            AssistantTheme {
                LocalPlayerScreen(
                    media = media,
                    onBack = { finish() }
                )
            }
        }
    }
}
