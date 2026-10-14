package com.babakriazi.bsc.template

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val url = assets.open("url.txt").bufferedReader().use { it.readText() }.trim()
            if (url.isNotEmpty()) {
                val uri = Uri.parse(
                    if (url.startsWith("http://") || url.startsWith("https://")) url
                    else "https://$url"
                )
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) {
        }
        finish()
    }
}
