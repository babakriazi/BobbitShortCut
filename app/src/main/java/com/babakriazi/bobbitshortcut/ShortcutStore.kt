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
                    iconPath = o.optString("iconPath", null)
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

    fun nextFreeSlot(): Int {
        val used = getAll().map { it.slot }.toSet()
        for (i in 0 until 100) {
            if (i !in used) return i
        }
        return 0
    }

    companion object {
        fun packageForSlot(slot: Int): String =
            "com.babakriazi.bsc.t" + slot.toString().padStart(2, '0')
    }
}
