package com.babakriazi.bsc.t00

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Reads target URL from assets/url.txt and opens it in the default browser.
 */
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
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (_: Exception) {
        }
        finish()
    }
}
