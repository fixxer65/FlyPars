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

    // ---- v2.4: уведомления «цена упала» ----

    /** Сохраняет последнюю известную минимальную цену направления (для сравнения при следующих загрузках). */
    fun saveLastKnownPrice(code: String, price: Int) {
        prefs.edit().putInt("$PRICE_PREFIX$code", price).apply()
    }

    fun getLastKnownPrice(code: String): Int? {
        val v = prefs.getInt("$PRICE_PREFIX$code", -1)
        return if (v > 0) v else null
    }

    /** Отметка времени последнего отправленного уведомления об удешевлении (0 = не было). */
    fun getLastNotifyTime(code: String): Long =
        prefs.getLong("$NOTIFY_PREFIX$code", 0L)

    fun setLastNotifyTime(code: String, timeMillis: Long) {
        prefs.edit().putLong("$NOTIFY_PREFIX$code", timeMillis).apply()
    }

    /** Пользовательский порог падения цены (в %), по умолчанию [DEFAULT_DROP_PERCENT]. */
    var dropThresholdPercent: Int
        get() = prefs.getInt(THRESHOLD_KEY, DEFAULT_DROP_PERCENT)
        set(value) = prefs.edit().putInt(THRESHOLD_KEY, value.coerceIn(1, 90)).apply()

    /** v2.6: режим уведомления — "percent" (процент) или "amount" (конкретная сумма ₽). */
    var notifyMode: String
        get() = prefs.getString(NOTIFY_MODE_KEY, MODE_PERCENT) ?: MODE_PERCENT
        set(value) = prefs.edit().putString(NOTIFY_MODE_KEY, value).apply()

    /** v2.6: порог в рублях для режима "amount" (0 = не задан). */
    var dropThresholdAmount: Int
        get() = prefs.getInt(AMOUNT_KEY, 0)
        set(value) = prefs.edit().putInt(AMOUNT_KEY, value.coerceIn(0, 1_000_000)).apply()

    /** v2.6: базовая цена для уведомлений — только «туда» или «туда и обратно». */
    var notifyRoundTrip: Boolean
        get() = prefs.getBoolean(NOTIFY_RT_KEY, false)
        set(value) = prefs.edit().putBoolean(NOTIFY_RT_KEY, value).apply()

    // ---- v2.7: целевая цена для отдельного избранного направления ----

    /** Целевая цена направления, ₽ (0 = не задана). Задается на карточке ⭐. */
    fun getTargetPrice(code: String): Int {
        val v = prefs.getInt("$TARGET_PREFIX$code", 0)
        return if (v > 0) v else 0
    }

    fun setTargetPrice(code: String, price: Int) {
        val e = prefs.edit()
        if (price > 0) e.putInt("$TARGET_PREFIX$code", price) else e.remove("$TARGET_PREFIX$code")
        e.apply()
    }

    /** Все заданные целевые цены: ключ направления -> сумма ₽. */
    fun allTargetPrices(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(TARGET_PREFIX) && v is Int && v > 0) {
                result[k.removePrefix(TARGET_PREFIX)] = v
            }
        }
        return result
    }

    /** Отметка времени последнего «целевого» уведомления (отдельный анти-спам от «падения»). */
    fun getLastTargetNotifyTime(code: String): Long =
        prefs.getLong("$TARGET_NOTIFIED_PREFIX$code", 0L)

    fun setLastTargetNotifyTime(code: String, timeMillis: Long) {
        prefs.edit().putLong("$TARGET_NOTIFIED_PREFIX$code", timeMillis).apply()
    }

    /** Флаг «уведомления включены», по умолчанию выключен (чтобы не спамить без разрешения). */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(NOTIF_ENABLED_KEY, false)
        set(value) = prefs.edit().putBoolean(NOTIF_ENABLED_KEY, value).apply()

    companion object {
        private const val KEY = "favorite_routes"
        private const val SNAPSHOT_KEY = "favorite_snapshot"
        private const val PRICE_PREFIX = "last_price_"
        private const val NOTIFY_PREFIX = "last_notify_"
        private const val THRESHOLD_KEY = "drop_threshold"
        private const val NOTIF_ENABLED_KEY = "notifications_enabled"
        private const val NOTIFY_MODE_KEY = "notify_mode"
        private const val AMOUNT_KEY = "drop_amount"
        private const val NOTIFY_RT_KEY = "notify_roundtrip"
        private const val TARGET_PREFIX = "target_price_"
        private const val TARGET_NOTIFIED_PREFIX = "target_notified_"
        const val DEFAULT_DROP_PERCENT = 10
        const val MODE_PERCENT = "percent"
        const val MODE_AMOUNT = "amount"
    }
}
