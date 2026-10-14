package com.babakriazi.bobbitshortcut

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds a signed mini-APK from the embedded template:
 * - unique package com.babakriazi.bsc.tXX
 * - label (32-char padded ASCII)
 * - url in assets/url.txt
 * - launcher icon PNGs replaced when possible
 */
object ApkBuilder {

    private const val BASE_PKG = "com.babakriazi.bsc.t00"
    private const val LABEL_PLACEHOLDER = "__BOBBIT_LABEL_PAD_32CHARS__" // 32 chars

    fun build(
        context: Context,
        slot: Int,
        label: String,
        url: String,
        iconPng: File?
    ): File {
        val slotStr = slot.toString().padStart(2, '0')
        val newPkg = "com.babakriazi.bsc.t$slotStr"
        require(newPkg.length == BASE_PKG.length)

        val workDir = File(context.cacheDir, "apk_work_$slotStr").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val unsigned = File(workDir, "unsigned.apk")
        val signed = File(context.cacheDir, "apks/shortcut_$slotStr.apk").also {
            it.parentFile?.mkdirs()
        }

        // 1) Load template from assets
        val templateBytes = context.assets.open("template.apk").use { it.readBytes() }

        // 2) Unzip, modify entries, re-zip
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(templateBytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    entries[e.name] = zis.readBytes()
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        // url.txt
        entries["assets/url.txt"] = (normalizeUrl(url) + "\n").toByteArray(Charsets.UTF_8)

        // Replace package name everywhere (same length)
        val pkgFrom = BASE_PKG.toByteArray(Charsets.UTF_8)
        val pkgTo = newPkg.toByteArray(Charsets.UTF_8)
        val labelFrom = LABEL_PLACEHOLDER.toByteArray(Charsets.UTF_8)
        val labelTo = padLabel(label).toByteArray(Charsets.UTF_8)
        require(labelFrom.size == labelTo.size)

        for ((name, data) in entries.toList()) {
            var bytes = data
            bytes = replaceBytes(bytes, pkgFrom, pkgTo)
            bytes = replaceBytes(bytes, labelFrom, labelTo)
            entries[name] = bytes
        }

        // Replace icons if we have a PNG
        if (iconPng != null && iconPng.exists()) {
            val png = iconPng.readBytes()
            val iconKeys = entries.keys.filter {
                it.endsWith("ic_launcher.png") || it.endsWith("ic_launcher.webp")
            }
            // Prefer writing as PNG paths; if only adaptive XML exists, also drop a PNG into drawable
            if (iconKeys.isNotEmpty()) {
                iconKeys.forEach { entries[it] = png }
            } else {
                entries["res/drawable/ic_launcher_foreground.png"] = png
                // Also try common density paths
                listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { dens ->
                    entries["res/mipmap-$dens/ic_launcher.png"] = png
                }
            }
        }

        // Write zip (no compression for .so alignment friendliness; STORED for most)
        ZipOutputStream(FileOutputStream(unsigned)).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                // Deflate by default
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }

        // 3) Sign
        signApk(context, unsigned, signed)
        workDir.deleteRecursively()
        return signed
    }

    private fun normalizeUrl(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    /** Pad/truncate to exactly 32 ASCII chars for binary replace. */
    private fun padLabel(name: String): String {
        val ascii = buildString {
            for (c in name) {
                if (c.code in 32..126) append(c) else append('?')
            }
        }.ifBlank { "Web Shortcut" }
        return ascii.take(32).padEnd(32, ' ')
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
            } else {
                i++
            }
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
        val signer = ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign()
    }
}
