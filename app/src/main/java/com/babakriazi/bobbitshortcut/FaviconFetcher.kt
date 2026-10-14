package com.babakriazi.bobbitshortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.appcompat.content.res.AppCompatResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object FaviconFetcher {

    suspend fun fetchAndSave(context: Context, siteUrl: String, outFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val urls = candidateIconUrls(siteUrl)
            for (u in urls) {
                try {
                    val bmp = downloadBitmap(u) ?: continue
                    if (bmp.width < 8 || bmp.height < 8) continue
                    val sized = Bitmap.createScaledBitmap(bmp, 192, 192, true)
                    FileOutputStream(outFile).use {
                        sized.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    return@withContext true
                } catch (_: Exception) {
                }
            }
            // default globe icon
            saveDefaultIcon(context, outFile)
            true
        }

    fun saveDefaultIcon(context: Context, outFile: File) {
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_default_site)
            ?: return
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, 192, 192)
        drawable.draw(canvas)
        FileOutputStream(outFile).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun candidateIconUrls(siteUrl: String): List<String> {
        val normalized = if (siteUrl.startsWith("http")) siteUrl else "https://$siteUrl"
        val base = try {
            val u = URL(normalized)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) {
            return emptyList()
        }
        val host = try { URL(normalized).host } catch (_: Exception) { "" }
        return listOf(
            "$base/apple-touch-icon.png",
            "$base/apple-touch-icon-precomposed.png",
            "$base/favicon-32x32.png",
            "$base/favicon-96x96.png",
            "$base/favicon.ico",
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
            "https://icons.duckduckgo.com/ip3/$host.ico"
        )
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 BobbitShortCut")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
