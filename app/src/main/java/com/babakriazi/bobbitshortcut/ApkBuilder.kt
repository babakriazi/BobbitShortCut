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
 * Critical for install success:
 *  - strip original META-INF (old signatures)
 *  - keep resources.arsc uncompressed (STORED)
 *  - re-sign with our keystore
 */
object ApkBuilder {

    private const val BASE_PKG = "com.babakriazi.bsc.t00"
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
        if (newPkg.length != BASE_PKG.length) {
            throw IllegalStateException("Package length mismatch: $newPkg vs $BASE_PKG")
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

        // name -> (bytes, method) method: ZipEntry.STORED or DEFLATED
        data class Entry(val data: ByteArray, val method: Int)

        val entries = linkedMapOf<String, Entry>()
        ZipInputStream(templateBytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && !name.uppercase().startsWith("META-INF/")) {
                    val data = zis.readBytes()
                    // Preserve STORED for arsc and similar; default DEFLATED otherwise
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

        // URL asset
        entries["assets/url.txt"] = Entry(
            (normalizeUrl(url) + "\n").toByteArray(Charsets.UTF_8),
            ZipEntry.DEFLATED
        )

        val pkgFrom = BASE_PKG.toByteArray(Charsets.UTF_8)
        val pkgTo = newPkg.toByteArray(Charsets.UTF_8)
        val labelFrom = LABEL_PLACEHOLDER.toByteArray(Charsets.UTF_8)
        val labelTo = padLabel(label).toByteArray(Charsets.UTF_8)
        if (labelFrom.size != labelTo.size) {
            throw IllegalStateException(
                "Label length mismatch: placeholder=${labelFrom.size} padded=${labelTo.size}"
            )
        }

        for ((name, entry) in entries.toList()) {
            var bytes = entry.data
            bytes = replaceBytes(bytes, pkgFrom, pkgTo)
            bytes = replaceBytes(bytes, labelFrom, labelTo)
            entries[name] = Entry(bytes, entry.method)
        }

        // Inject favicon as mipmap PNGs (STORED)
        if (iconPng != null && iconPng.exists()) {
            val png = iconPng.readBytes()
            val iconKeys = entries.keys.filter {
                it.endsWith("ic_launcher.png") || it.endsWith("ic_launcher.webp")
            }
            if (iconKeys.isNotEmpty()) {
                iconKeys.forEach { entries[it] = Entry(png, ZipEntry.STORED) }
            } else {
                listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { dens ->
                    entries["res/mipmap-$dens/ic_launcher.png"] = Entry(png, ZipEntry.STORED)
                }
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
