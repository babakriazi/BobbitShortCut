package com.babakriazi.bobbitshortcut

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var store: ShortcutStore
    private lateinit var adapter: ShortcutAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = ShortcutStore(this)
        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.txtEmpty)
        progress = findViewById(R.id.progressOverlay)

        adapter = ShortcutAdapter(
            onOpen = { openUrl(it.url) },
            onInstall = { installExisting(it) },
            onDelete = { deleteShortcut(it) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { createShortcut() }
        refreshList()
    }

    private fun createShortcut() {
        val name = findViewById<TextInputEditText>(R.id.edtName).text?.toString()?.trim().orEmpty()
        val url = findViewById<TextInputEditText>(R.id.edtUrl).text?.toString()?.trim().orEmpty()

        if (name.isBlank()) {
            Toast.makeText(this, R.string.error_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (url.isBlank() || (!url.contains('.') && !url.startsWith("http"))) {
            Toast.makeText(this, R.string.error_url, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Install permission")
                .setMessage("Allow installing apps from Bobbit ShortCut so shortcuts can appear in your app list.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val slot = store.nextFreeSlot()
                val pkg = ShortcutStore.packageForSlot(slot)
                val iconFile = File(cacheDir, "icon_$slot.png")
                FaviconFetcher.fetchAndSave(this@MainActivity, url, iconFile)

                val apk = withContext(Dispatchers.IO) {
                    ApkBuilder.build(this@MainActivity, slot, name, url, iconFile)
                }

                val savedIcon = File(filesDir, "icons/$slot.png").also { it.parentFile?.mkdirs() }
                if (iconFile.exists()) iconFile.copyTo(savedIcon, overwrite = true)

                val item = SiteShortcut(
                    name = name,
                    url = normalizeUrl(url),
                    slot = slot,
                    packageName = pkg,
                    iconPath = savedIcon.absolutePath
                )
                store.add(item)
                refreshList()

                findViewById<TextInputEditText>(R.id.edtName).text?.clear()
                findViewById<TextInputEditText>(R.id.edtUrl).text?.clear()

                installApk(apk)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun installExisting(item: SiteShortcut) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val iconFile = item.iconPath?.let { File(it) }
                val apk = withContext(Dispatchers.IO) {
                    ApkBuilder.build(
                        this@MainActivity,
                        item.slot,
                        item.name,
                        item.url,
                        iconFile
                    )
                }
                installApk(apk)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Toast.makeText(this, R.string.install_prompt, Toast.LENGTH_LONG).show()
        startActivity(intent)
    }

    private fun deleteShortcut(item: SiteShortcut) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("Remove \"${item.name}\" from the list?\n(Uninstall the shortcut app from system settings if it is installed.)")
            .setPositiveButton(R.string.delete) { _, _ ->
                store.remove(item.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(url))))
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeUrl(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun refreshList() {
        val list = store.getAll()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class ShortcutAdapter(
        val onOpen: (SiteShortcut) -> Unit,
        val onInstall: (SiteShortcut) -> Unit,
        val onDelete: (SiteShortcut) -> Unit
    ) : RecyclerView.Adapter<ShortcutAdapter.VH>() {

        private var items = listOf<SiteShortcut>()

        fun submit(list: List<SiteShortcut>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shortcut, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val img: ImageView = itemView.findViewById(R.id.imgIcon)
            private val name: TextView = itemView.findViewById(R.id.txtName)
            private val url: TextView = itemView.findViewById(R.id.txtUrl)
            private val btnOpen: ImageButton = itemView.findViewById(R.id.btnOpen)
            private val btnInstall: ImageButton = itemView.findViewById(R.id.btnInstall)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(s: SiteShortcut) {
                name.text = s.name
                url.text = s.url
                val iconFile = s.iconPath?.let { File(it) }
                if (iconFile != null && iconFile.exists()) {
                    img.setImageURI(Uri.fromFile(iconFile))
                } else {
                    img.setImageResource(R.drawable.ic_default_site)
                }
                btnOpen.setOnClickListener { onOpen(s) }
                btnInstall.setOnClickListener { onInstall(s) }
                btnDelete.setOnClickListener { onDelete(s) }
            }
        }
    }
}
