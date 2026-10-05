package ru.pobedamonitor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Локальная история минимальных цен по направлениям (SharedPreferences, JSON).
 *
 * При каждой успешной загрузке цен запоминаем минимальную цену дня для каждого
 * направления: "hubIata-arrivalIata" -> (дата ISO -> цена RUB). Храним не более
 * [MAX_DAYS] последних наблюдений на направление.
 *
 * На основе накопленных данных строится прогноз «выгодно / обычно / дорого»:
 * сравниваем текущую минимальную цену с медианой предыдущих наблюдений
 * (текущий день в сравнение не входит).
 */
class PriceHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences("price_history", Context.MODE_PRIVATE)

    /** Все записи истории: ключ направления -> (дата ISO -> цена). */
    fun loadAll(): Map<String, Map<String, Int>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, Map<String, Int>>()
            for (code in obj.keys()) {
                val days = obj.getJSONObject(code)
                val map = mutableMapOf<String, Int>()
                for (iso in days.keys()) map[iso] = days.getInt(iso)
                result[code] = map
            }
            result
        }.getOrDefault(emptyMap())
    }

    /** История одного направления, отсортированная по дате (старые -> новые). */
    fun historyFor(code: String): List<Pair<LocalDate, Int>> =
        loadAll()[code].orEmpty()
            .mapNotNull { (iso, price) -> runCatching { LocalDate.parse(iso) to price }.getOrNull() }
            .sortedBy { it.first }

    /** Добавляет/обновляет наблюдение цены [price] на сегодня для [code]. */
    fun record(code: String, date: LocalDate, price: Int) {
        val all = loadAll().toMutableMap()
        val days = all.getOrPut(code) { mutableMapOf<String, Int>() as MutableMap<String, Int> }
            .toMutableMap()
        days[date.toString()] = price
        // обрезаем до последних MAX_DAYS наблюдений
        if (days.size > MAX_DAYS) {
            val trimmed = days.entries.sortedBy { it.key }.takeLast(MAX_DAYS).associate { it.key to it.value }
            all[code] = trimmed
        } else {
            all[code] = days
        }
        save(all)
    }

    /** Пакетное сохранение: directionKey -> сегодняшняя минимальная цена. */
    fun recordAll(prices: Map<String, Int>, date: LocalDate = LocalDate.now()) {
        if (prices.isEmpty()) return
        val all = loadAll().toMutableMap()
        prices.forEach { (code, price) ->
            val days = all.getOrPut(code) { emptyMap() }.toMutableMap()
            days[date.toString()] = price
            all[code] = if (days.size > MAX_DAYS) {
                days.entries.sortedBy { it.key }.takeLast(MAX_DAYS).associate { it.key to it.value }
            } else days
        }
        save(all)
    }

    private fun save(all: Map<String, Map<String, Int>>) {
        val obj = JSONObject()
        all.forEach { (code, days) ->
            obj.put(code, JSONObject().apply { days.forEach { (d, p) -> put(d, p) } })
        }
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    /** Оценка выгодности текущей цены [currentPrice] по истории [history] (без текущего дня). */
    enum class Verdict { GOOD, NEUTRAL, BAD }

    data class Assessment(val verdict: Verdict, val median: Int, val diffPercent: Int)

    /**
     * Возвращает оценку, если накоплено минимум [MIN_SAMPLES] предыдущих наблюдений.
     * Порог: ±[THRESHOLD_PERCENT]% от медианы.
     */
    fun assess(code: String, currentPrice: Int?, today: LocalDate = LocalDate.now()): Assessment? {
        if (currentPrice == null) return null
        val prev = historyFor(code).filter { it.first != today }.map { it.second }
        if (prev.size < MIN_SAMPLES) return null
        val sorted = prev.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        if (median <= 0) return null
        val diff = ((currentPrice - median) * 100.0 / median).toInt()
        val verdict = when {
            diff <= -THRESHOLD_PERCENT -> Verdict.GOOD
            diff >= THRESHOLD_PERCENT -> Verdict.BAD
            else -> Verdict.NEUTRAL
        }
        return Assessment(verdict, median, diff)
    }

    companion object {
        private const val KEY = "price_history"
        const val MAX_DAYS = 90
        const val MIN_SAMPLES = 3
        const val THRESHOLD_PERCENT = 15
    }
}
