package ru.pobedamonitor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Локальное хранилище избранных направлений (SharedPreferences, JSON).
 * Ключ направления: "hubIata-arrivalIata" (например, "MOW-AER").
 */
class FavoritesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    /** Множество ключей избранных направлений. */
    fun load(): Set<String> {
        val raw = prefs.getString(KEY, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun save(codes: Set<String>) {
        val arr = JSONArray().apply { codes.forEach { put(it) } }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun isFavorite(code: String): Boolean = code in load()

    /** Возвращает новое множество после переключения [code]. */
    fun toggle(code: String): Set<String> {
        val set = load().toMutableSet()
        if (!set.remove(code)) set.add(code)
        save(set)
        return set
    }

    /** Мгновенный снимок для виджета: список пар (имя города, цена RUB или null). */
    data class WidgetEntry(val name: String, val priceRub: Int?)

    fun saveSnapshot(entries: List<WidgetEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("name", e.name).apply {
                e.priceRub?.let { put("price", it) }
            })
        }
        prefs.edit().putString(SNAPSHOT_KEY, arr.toString()).apply()
    }

    fun loadSnapshot(): List<WidgetEntry> {
        val raw = prefs.getString(SNAPSHOT_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WidgetEntry(
                    name = o.optString("name"),
                    priceRub = if (o.has("price")) o.getInt("price") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "favorite_routes"
        private const val SNAPSHOT_KEY = "favorite_snapshot"
    }
}
