package ru.pobedamonitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Парсер цен авиакомпании «Победа» (flypobeda.ru).
 *
 * Использует внутренний API сайта (тот же, что использует страница
 * «Лучшие предложения» на https://www.flypobeda.ru):
 *   GET https://site-api.flypobeda.ru/best-offers/multi
 *       ?locale=ru&departure[0]=MOW|MSQ&dates[0]=YYYY-MM-DD
 *
 * Ответ — массив направлений с минимальной ценой на запрошенную дату.
 */
class PobedaRepository {

    companion object {
        const val API_BASE = "https://site-api.flypobeda.ru/best-offers/multi"
        /**
         * v2.14: реальное РАСПИСАНИЕ рейсов (время вылета по местному часовому
         * поясу аэропорта). Поле flightTime в best-offers — это ДЛИТЕЛЬНОСТЬ
         * перелёта, а не время вылета; вылет берём только отсюда.
         */
        const val TIMETABLE_API = "https://site-api.flypobeda.ru/flight-timetable"
        const val SITE_URL = "https://www.flypobeda.ru"

        /** Аэропорты вылета из Москвы (Минск обслуживается одним аэропортом MSQ). */
        val MOSCOW_AIRPORTS = listOf(
            Airport("VKO", "Внуково"),
            Airport("SVO", "Шереметьево"),
            Airport("DME", "Домодедово"),
            Airport("ZIA", "Жуковский"),
        )

        val HUBS = listOf(
            Hub("MOW", "Москва", MOSCOW_AIRPORTS),
            Hub("MSQ", "Минск", listOf(Airport("MSQ", "Минск (Национальный аэропорт)"))),
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val isoFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Один тариф/предложение на конкретную дату по конкретному направлению. */
    data class PriceEntry(
        val depDate: String,      // ISO yyyy-MM-dd
        val price: Int,           // RUB
        val airline: String?,
        val flightTime: String?,
        /** v2.13: «DP,DP» — стыковочный (несколько плечей), «DP» — прямой. */
        val legsCount: Int = 1,
        /** v2.13: время вылета из ответа API (HH:mm или H:mm). */
        val depTime: String? = null,
    ) {
        /** Прямой рейс (одно плечо) или стыковочный (через другой город). */
        val isDirect: Boolean get() = legsCount <= 1

        /**
         * v2.13: подпись формата «09 окт · 14:55 · прямой» / «… · стыковочный».
         * [dateFmt] — формат даты (например, d MMM); без времени возвращает только дату.
         */
        fun flightLabel(dateFmt: DateTimeFormatter): String {
            val sb = StringBuilder()
            runCatching { LocalDate.parse(depDate).format(dateFmt) }
                .onSuccess { sb.append(it) }
                .onFailure { sb.append(depDate) }
            depTime?.let { sb.append(" · ").append(normalizeTime(it)) }
            sb.append(if (isDirect) " · прямой" else " · стыковочный")
            return sb.toString()
        }

        companion object {
            /** Приводит «1:30»/«24:00» к виду «01:30»; значения не-времени скрывает. */
            fun normalizeTime(raw: String?): String? {
                if (raw.isNullOrBlank()) return null
                val parts = raw.trim().split(":")
                if (parts.size != 2) return null
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                if (m !in 0..59) return null
                return "%02d:%02d".format(h % 24, m)
            }
        }
    }

    /** Направление + цены по всем запрошенным датам. */
    data class RoutePrices(
        val hubIata: String,
        val arrivalIata: String,
        val arrivalName: String,
        val prices: Map<String, PriceEntry>,   // date -> entry
    ) {
        val cheapest: PriceEntry? get() = prices.values.minByOrNull { it.price }

        /**
         * v2.14: лучшая цена среди дат, удовлетворяющих предикату [keepDate]
         * (например, только выбранные дни недели режима «выходных»).
         */
        fun cheapestFiltered(keepDate: (LocalDate) -> Boolean): PriceEntry? =
            prices.values.filter { e ->
                runCatching { keepDate(LocalDate.parse(e.depDate)) }.getOrDefault(false)
            }.minByOrNull { it.price }
    }

    data class FetchResult(
        val routes: List<RoutePrices>,
        val errors: List<String>,
    )

    /**
     * Загружает цены по всем направлениям из указанных хабов (по умолчанию —
     * Москва MOW и Минск MSQ) на диапазон дат [from]..[to] включительно.
     */
    suspend fun fetchPrices(
        from: LocalDate,
        to: LocalDate,
        hubs: List<String> = HUBS.map { it.iata },
        onlyDates: Set<LocalDate>? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        // В режиме «выходных» запрашиваем только выбранные даты (например, пятницы),
        // в обычном — все дни диапазона подряд.
        val dates = if (!onlyDates.isNullOrEmpty()) {
            onlyDates.sorted()
        } else {
            generateSequence(from) { if (it < to) it.plusDays(1) else null }.toList()
        }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val routes: List<RoutePrices> = coroutineScope {
            hubs.flatMap { hub ->
                dates.map { date ->
                    async {
                        try {
                            val json = httpGetJson(buildUrl(hub, date))
                            parseDay(hub, json)
                        } catch (e: Exception) {
                            errors.add("$hub ${date}: ${e.message ?: e.javaClass.simpleName}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }

        // склеиваем одинаковые направления в одну запись со словарём дат
        val merged = routes
            .groupBy { it.hubIata to it.arrivalIata }
            .values
            .map { parts ->
                RoutePrices(
                    hubIata = parts.first().hubIata,
                    arrivalIata = parts.first().arrivalIata,
                    arrivalName = parts.first().arrivalName,
                    prices = parts.flatMap { p -> p.prices.values }
                        .filter { e ->
                            // В режиме «выходных» API может вернуть соседние даты —
                            // оставляем только запрошенные.
                            onlyDates.isNullOrEmpty() ||
                                runCatching { LocalDate.parse(e.depDate) in onlyDates }.isSuccess
                        }
                        .associateBy { it.depDate },
                )
            }
            .sortedWith(
                compareBy<RoutePrices> { if (it.hubIata == "MOW") 0 else 1 }
                    .thenBy { it.cheapest?.price ?: Int.MAX_VALUE }
            )

        FetchResult(merged, errors.toList())
    }

    /**
     * Обратные цены для одного направления arrival -> hub на список дат [dates].
     * Если передан непустой [onlyDays], в результат попадают только даты,
     * дни недели которых выбраны (v2.14: уведомления учитывают дни «обратно»).
     */
    suspend fun fetchReturnPrices(
        hubIata: String,
        arrivalIata: String,
        dates: List<LocalDate>,
        onlyDays: Set<java.time.DayOfWeek> = emptySet(),
    ): Map<String, PriceEntry> = withContext(Dispatchers.IO) {
        coroutineScope {
            dates.map { date ->
                async {
                    try {
                        val json = httpGetJson(buildUrl(arrivalIata, date))
                        parseDay(hubIata, json)
                            .firstOrNull { it.arrivalIata == hubIata }
                            ?.prices?.values?.minByOrNull { it.price }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
                .filter { e ->
                    onlyDays.isEmpty() || runCatching {
                        LocalDate.parse(e.depDate).dayOfWeek in onlyDays
                    }.getOrDefault(false)
                }
                .associateBy { it.depDate }
        }
    }

    private fun buildUrl(hub: String, date: LocalDate): String =
        "$API_BASE?locale=ru&departure%5B0%5D=$hub&dates%5B0%5D=${date.format(isoFmt)}"

    /**
     * Запрашивает все направления из [departureIata] на список дат [dates] одним
     * запросом (API поддерживает несколько параметров dates[N]). Используется для
     * обратных билетов: ответ содержит minOffer на каждую запрошенную дату.
     */
    suspend fun fetchDirectionJson(departureIata: String, dates: List<LocalDate>): JSONArray =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder(API_BASE)
                .append("?locale=ru&departure%5B0%5D=").append(departureIata)
            dates.forEachIndexed { idx, d ->
                sb.append("&dates%5B").append(idx).append("%5D=").append(d.format(isoFmt))
            }
            httpGetJson(sb.toString())
        }

    private fun httpGetJson(url: String): JSONArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json")
            .header("Referer", SITE_URL)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            return JSONArray(body)
        }
    }

    private fun httpGetObject(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json")
            .header("Referer", SITE_URL)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            return JSONObject(body)
        }
    }

    // ---- v2.14: расписание рейсов (реальное время вылета) -------------------

    /** Кэш расписания: дата ISO -> ("откудаIATA-кудаIATA" -> HH:mm местного вылета). */
    private val timetableCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** Кэш «не удалось получить расписание на дату» — не долбить API при каждой загрузке. */
    private val timetableMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * v2.14: расписание Победы отдаёт данные только на ближайшие несколько дней;
     * для дальней даты пробуем соседние (API иногда сдвигает окно на ±1 день).
     */
    private suspend fun fetchTimetableAround(date: LocalDate): Map<String, String> {
        for (d in listOf(date, date.plusDays(1), date.minusDays(1))) {
            val m = fetchTimetableDepartureTimes(d.format(isoFmt))
            if (m.isNotEmpty()) return m
        }
        return emptyMap()
    }

    /**
     * Возвращает карту «$from-$to» -> время вылета (HH:mm, местное время аэропорта)
     * на дату [dateIso] из официального расписания flypobeda.ru. Пустая карта —
     * если расписание на дату недоступно (например, дата дальше ~3 дней).
     */
    suspend fun fetchTimetableDepartureTimes(dateIso: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            timetableCache.getOrPut(dateIso) {
                // Дата вне окна расписания? Повторим попытку не раньше чем через 6 часов.
                val missedAt = timetableMisses[dateIso] ?: 0L
                if (System.currentTimeMillis() - missedAt < 6L * 60 * 60 * 1000) return@getOrPut emptyMap()
                val map = runCatching {
                    val json = httpGetObject("$TIMETABLE_API?locale=ru&date=$dateIso")
                    val flights = json.optJSONArray("flights") ?: return@runCatching emptyMap()
                    val map = HashMap<String, String>(flights.length())
                    for (i in 0 until flights.length()) {
                        val f = flights.optJSONObject(i) ?: continue
                        val dep = f.optJSONObject("departure") ?: continue
                        val arr = f.optJSONObject("arrival") ?: continue
                        val from = dep.optString("iata").takeIf { it.isNotBlank() } ?: continue
                        val to = arr.optString("iata").takeIf { it.isNotBlank() } ?: continue
                        // stdLocal — время вылета по МЕСТНОМУ часовому поясу аэропорта.
                        val stdLocal = dep.optString("stdLocal").takeIf { it.isNotBlank() && !it.startsWith("01.01.1900") }
                        val std = dep.optString("std").takeIf { it.isNotBlank() && !it.startsWith("01.01.1900") }
                        val raw = (stdLocal ?: std)?.substringAfter('T')?.take(5) ?: continue
                        val h = raw.substringBefore(':').toIntOrNull() ?: continue
                        val m = raw.substringAfter(':').toIntOrNull() ?: continue
                        if (h in 0..23 && m in 0..59) {
                            // Победа выполняет не более одного рейса в день по паре
                            // аэропортов — просто перезаписываем на всякий случай.
                            map["$from-$to"] = "%02d:%02d".format(h, m)
                        }
                    }
                    map
                }.getOrDefault(emptyMap())
            }
        }

    /**
     * Подставляет в каждый [PriceEntry] реальное время вылета из расписания
     * (best-effort: даты дальше ~3 дней или отсутствующее направление остаются
     * без времени). Возвращает обновлённый список маршрутов.
     */
    suspend fun enrichWithDepartureTimes(routes: List<RoutePrices>): List<RoutePrices> {
        val dates = routes.flatMap { r -> r.prices.keys }.distinct()
        if (dates.isEmpty()) return routes
        val tables = dates.map { d -> d to fetchTimetableDepartureTimes(d) }.toMap()
        return routes.map { r ->
            val newPrices = r.prices.mapValues { (date, e) ->
                val t = tables[date]?.get("${r.hubIata}-${r.arrivalIata}")
                    ?: tables[date]?.let { tbl -> MOSCOW_AIRPORTS.firstNotNulls(tbl, r.arrivalIata) }
                if (t != null && t != e.depTime) e.copy(depTime = t) else e
            }
            r.copy(prices = newPrices)
        }
    }

    /** Для московского хаба MOW ищем рейс из любого аэропорта Внуково/Шереметьево/... */
    private fun firstNotNulls(table: Map<String, String>, arrivalIata: String): String? =
        MOSCOW_AIRPORTS.firstNotNullOfOrNull { table["${it.iata}-$arrivalIata"] }

    private fun parseDay(hub: String, arr: JSONArray): List<RoutePrices> {
        val result = ArrayList<RoutePrices>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val arrival = obj.optJSONObject("arrival") ?: continue
            val minOffer = obj.optJSONObject("minOffer") ?: continue
            val price = minOffer.optInt("price", -1)
            if (price <= 0) continue
            val depDate = minOffer.optString("depDate")
            if (depDate.isBlank()) continue
            val airlineRaw = minOffer.optString("airline").takeIf { it.isNotBlank() }
            val rawFlightTime = minOffer.optString("flightTime").takeIf { it.isNotBlank() }
            // v2.13: поле flightTime в API — время вылета (HH:mm). Длительность перелёта
            // для стыковок может превышать 24 ч (например «24:00») — не считаем её временем.
            val depTime = runCatching {
                val parts = rawFlightTime?.trim()?.split(":") ?: error("blank")
                if (parts.size != 2) error("format")
                val h = parts[0].toInt()
                val m = parts[1].toInt()
                require(h in 0..23 && m in 0..59)
                "%02d:%02d".format(h, m)
            }.getOrNull()
            val entry = PriceEntry(
                depDate = depDate,
                price = price,
                airline = airlineRaw,
                flightTime = rawFlightTime,
                legsCount = airlineRaw?.split(",")?.filter { it.isNotBlank() }?.size ?: 1,
                depTime = depTime,
            )
            result.add(
                RoutePrices(
                    hubIata = hub,
                    arrivalIata = arrival.optString("iataCode"),
                    arrivalName = arrival.optString("name"),
                    prices = mapOf(depDate to entry),
                )
            )
        }
        return result
    }
}

/** Город/хаб вылета и его аэропорты. */
data class Hub(val iata: String, val name: String, val airports: List<Airport>)

/** Аэропорт. */
data class Airport(val iata: String, val name: String)
