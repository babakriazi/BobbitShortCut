package com.babakriazi.bobbitshortcut

import android.content.Context
import com.android.apksig.ApkSigner
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
 * Builds a signed mini-APK from the embedded template.
 *
 * Package rename must cover BOTH:
 *  - dotted form  com.babakriazi.bsc.t00  (manifest / resources)
 *  - slashed form com/babakriazi/bsc/t00  (DEX class paths)
 *
 * Icons: remove adaptive XML so PNG mipmaps are actually used.
 */
object ApkBuilder {

    private const val BASE_PKG = "com.babakriazi.bsc.t00"
    private const val BASE_PKG_SLASH = "com/babakriazi/bsc/t00"
    private const val LABEL_PLACEHOLDER = "__BOBBIT_LABEL_PAD_32CHARS__"

    fun build(
        context: Context,
        slot: Int,
        label: String,
        url: String,
        iconPng: File?
    ): File {
        val slotStr = slot.toString().padStart(2, '0')
        val newPkg = "com.babakriazi.bsc.t$slotStr"
        val newPkgSlash = "com/babakriazi/bsc/t$slotStr"
        if (newPkg.length != BASE_PKG.length || newPkgSlash.length != BASE_PKG_SLASH.length) {
            throw IllegalStateException("Package length mismatch: $newPkg")
        }

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
            context.assets.open("template.apk").use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException("template.apk missing in assets", e)
        }

        data class Entry(val data: ByteArray, val method: Int)

        val entries = linkedMapOf<String, Entry>()
        ZipInputStream(templateBytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                // Skip signatures AND adaptive icons (they override PNG on API 26+)
                val skip = e.isDirectory ||
                    name.uppercase().startsWith("META-INF/") ||
                    name.contains("mipmap-anydpi") ||
                    name.endsWith("ic_launcher.xml") && name.contains("mipmap")
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

        val replacements = listOf(
            BASE_PKG.toByteArray(Charsets.UTF_8) to newPkg.toByteArray(Charsets.UTF_8),
            BASE_PKG_SLASH.toByteArray(Charsets.UTF_8) to newPkgSlash.toByteArray(Charsets.UTF_8),
            LABEL_PLACEHOLDER.toByteArray(Charsets.UTF_8) to padLabel(label).toByteArray(Charsets.UTF_8)
        )
        for ((from, to) in replacements) {
            if (from.size != to.size) {
                throw IllegalStateException("Replace length mismatch ${from.size} vs ${to.size}")
            }
        }

        for ((name, entry) in entries.toList()) {
            var bytes = entry.data
            for ((from, to) in replacements) {
                bytes = replaceBytes(bytes, from, to)
            }
            entries[name] = Entry(bytes, entry.method)
        }

        // Always inject PNG icons for all densities (custom or favicon)
        if (iconPng != null && iconPng.exists()) {
            val png = iconPng.readBytes()
            // Remove any leftover adaptive/xml launcher icons
            entries.keys.filter {
                it.contains("ic_launcher") && (it.endsWith(".xml") || it.contains("anydpi"))
            }.toList().forEach { entries.remove(it) }

            listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { dens ->
                entries["res/mipmap-$dens/ic_launcher.png"] = Entry(png, ZipEntry.STORED)
                entries["res/mipmap-$dens/ic_launcher_round.png"] = Entry(png, ZipEntry.STORED)
            }
            // Also plain mipmap fallback
            entries["res/mipmap/ic_launcher.png"] = Entry(png, ZipEntry.STORED)
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
            throw IllegalStateException("Signed APK invalid or too small")
        }

        workDir.deleteRecursively()
        return signed
    }

    private fun normalizeUrl(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun padLabel(name: String): String {
        val len = LABEL_PLACEHOLDER.length
        val ascii = buildString {
            for (c in name) {
                if (c.code in 32..126) append(c) else append('?')
            }
        }.ifBlank { "Web Shortcut" }
        return ascii.take(len).padEnd(len, ' ')
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
            .setMinSdkVersion(26)
            .build()
        signer.sign()
    }
}
