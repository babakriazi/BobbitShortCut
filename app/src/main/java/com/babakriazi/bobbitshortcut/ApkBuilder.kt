package com.babakriazi.bobbitshortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Label: fixed 64-byte ASCII placeholder replaced with UTF-8 bytes (Persian supported),
 * padded/truncated to exactly 64 bytes so resources.arsc stays valid.
 */
object ApkBuilder {

    // Must match template strings.xml app_name exactly (64 ASCII chars)
    private const val LABEL_PLACEHOLDER =
        "BOBBIT_SHORTCUT_LABEL_UTF8_PAD__________________________________"

    const val MAX_SLOTS = 20

    init {
        require(LABEL_PLACEHOLDER.length == 64) {
            "LABEL_PLACEHOLDER must be 64 chars, was ${LABEL_PLACEHOLDER.length}"
        }
    }

    fun build(
        context: Context,
        slot: Int,
        label: String,
        url: String,
        iconPng: File?
    ): File {
        require(slot in 0 until MAX_SLOTS) { "Slot out of range: $slot" }
        val slotStr = slot.toString().padStart(2, '0')

        val workDir = File(context.cacheDir, "apk_work_$slotStr").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val unsigned = File(workDir, "unsigned.apk")
        val signed = File(context.cacheDir, "apks/shortcut_$slotStr.apk").also {
            it.parentFile?.mkdirs()
            if (it.exists()) it.delete()
        }

        val templateBytes = try {
            context.assets.open("templates/t$slotStr.apk").use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException("Template for slot $slotStr missing", e)
        }

        data class Entry(val data: ByteArray, val method: Int)
        val entries = linkedMapOf<String, Entry>()

        ZipInputStream(templateBytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                val skip = e.isDirectory || name.uppercase().startsWith("META-INF/")
                if (!skip) {
                    val data = zis.readBytes()
                    val method = when {
                        name == "resources.arsc" -> ZipEntry.STORED
                        name.endsWith(".png") || name.endsWith(".webp") -> ZipEntry.STORED
                        e.method == ZipEntry.STORED -> ZipEntry.STORED
                        else -> ZipEntry.DEFLATED
                    }
                    entries[name] = Entry(data, method)
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        entries["assets/url.txt"] = Entry(
            (normalizeUrl(url) + "\n").toByteArray(Charsets.UTF_8),
            ZipEntry.DEFLATED
        )

        val labelFrom = LABEL_PLACEHOLDER.toByteArray(Charsets.UTF_8)
        val labelTo = padLabelUtf8(label)
        require(labelFrom.size == 64 && labelTo.size == 64)

        for ((name, entry) in entries.toList()) {
            entries[name] = Entry(replaceBytes(entry.data, labelFrom, labelTo), entry.method)
        }

        if (iconPng != null && iconPng.exists()) {
            val src = BitmapFactory.decodeFile(iconPng.absolutePath)
                ?: throw IllegalStateException("Cannot decode icon image")
            val densitySizes = mapOf(
                "mdpi" to 48,
                "hdpi" to 72,
                "xhdpi" to 96,
                "xxhdpi" to 144,
                "xxxhdpi" to 192
            )
            val iconKeys = entries.keys.filter { key ->
                val n = key.substringAfterLast('/')
                (n == "ic_launcher.png" || n == "ic_launcher_round.png") &&
                    key.contains("mipmap")
            }
            for (key in iconKeys) {
                val dens = densitySizes.keys.find { key.contains("mipmap-$it") }
                val size = dens?.let { densitySizes[it] } ?: 192
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
                entries[key] = Entry(baos.toByteArray(), ZipEntry.STORED)
            }
        }

        ZipOutputStream(FileOutputStream(unsigned)).use { zos ->
            for ((name, entry) in entries) {
                val zipEntry = ZipEntry(name)
                if (entry.method == ZipEntry.STORED) {
                    zipEntry.method = ZipEntry.STORED
                    zipEntry.size = entry.data.size.toLong()
                    zipEntry.compressedSize = entry.data.size.toLong()
                    val crc = CRC32()
                    crc.update(entry.data)
                    zipEntry.crc = crc.value
                } else {
                    zipEntry.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(zipEntry)
                zos.write(entry.data)
                zos.closeEntry()
            }
        }

        try {
            signApk(context, unsigned, signed)
        } catch (e: Exception) {
            throw IllegalStateException("APK signing failed: ${e.message}", e)
        }

        if (!signed.exists() || signed.length() < 1000) {
            throw IllegalStateException("Signed APK invalid")
        }
        workDir.deleteRecursively()
        return signed
    }

    private fun normalizeUrl(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    /**
     * Encode [name] as UTF-8, truncate on character boundary to max 64 bytes,
     * pad with spaces (0x20) to exactly 64 bytes. Supports Persian and other Unicode.
     */
    private fun padLabelUtf8(name: String): ByteArray {
        val max = 64
        val clean = name.trim().ifBlank { "Web Shortcut" }
        var bytes = clean.toByteArray(Charsets.UTF_8)
        if (bytes.size > max) {
            var cut = max
            while (cut > 0 && cut < bytes.size && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            bytes = bytes.copyOf(cut.coerceAtLeast(0))
        }
        val out = ByteArray(max) { 0x20 }
        System.arraycopy(bytes, 0, out, 0, bytes.size.coerceAtMost(max))
        return out
    }

    private fun replaceBytes(source: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        if (from.size != to.size) return source
        val out = source.copyOf()
        var i = 0
        while (i <= out.size - from.size) {
            var match = true
            for (j in from.indices) {
                if (out[i + j] != from[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                System.arraycopy(to, 0, out, i, to.size)
                i += to.size
            } else i++
        }
        return out
    }

    private fun signApk(context: Context, input: File, output: File) {
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open("shortcut.p12").use { stream ->
            ks.load(stream, "bobbit".toCharArray())
        }
        val alias = ks.aliases().nextElement()
        val privateKey = ks.getKey(alias, "bobbit".toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val config = ApkSigner.SignerConfig.Builder("Bobbit", privateKey, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }
}
