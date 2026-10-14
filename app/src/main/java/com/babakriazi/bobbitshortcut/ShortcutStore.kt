package com.babakriazi.bobbitshortcut

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SiteShortcut(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val slot: Int,
    val packageName: String,
    val iconPath: String? = null
)

class ShortcutStore(context: Context) {
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun getAll(): MutableList<SiteShortcut> {
        val json = prefs.getString("list", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<SiteShortcut>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                SiteShortcut(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    slot = o.optInt("slot"),
                    packageName = o.optString("packageName"),
                    iconPath = o.optString("iconPath", null).takeIf { !it.isNullOrBlank() }
                )
            )
        }
        return list
    }

    fun saveAll(list: List<SiteShortcut>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("slot", s.slot)
                put("packageName", s.packageName)
                put("iconPath", s.iconPath ?: "")
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun add(s: SiteShortcut) {
        val list = getAll()
        list.add(0, s)
        saveAll(list)
    }

    fun remove(id: String) {
        saveAll(getAll().filter { it.id != id })
    }

    /** Prefer slots not used in our list and not already installed on device. */
    fun nextFreeSlot(): Int {
        val usedInList = getAll().map { it.slot }.toSet()
        val pm = appContext.packageManager
        for (i in 0 until ApkBuilder.MAX_SLOTS) {
            if (i in usedInList) continue
            val pkg = packageForSlot(i)
            try {
                pm.getPackageInfo(pkg, 0)
                // installed — skip
            } catch (_: Exception) {
                return i
            }
        }
        // fallback: first not in list
        for (i in 0 until ApkBuilder.MAX_SLOTS) {
            if (i !in usedInList) return i
        }
        throw IllegalStateException("All ${ApkBuilder.MAX_SLOTS} shortcut slots are in use. Delete one first.")
    }

    companion object {
        fun packageForSlot(slot: Int): String =
            "com.babakriazi.bsc.t" + slot.toString().padStart(2, '0')
    }
}
