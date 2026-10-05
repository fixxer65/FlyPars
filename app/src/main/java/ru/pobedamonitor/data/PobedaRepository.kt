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

        /** v2.27: страница поиска билетов — для прямого перехода на выбранный рейс. */
        const val SEARCH_URL = "https://www.flypobeda.ru/mobile/search"

        /**
         * Ссылка на страницу подбора билетов flypobeda.ru с предзаполненными
         * параметрами выбранного рейса (v2.27). Формат параметров совпадает с
         * реальными URL сайта: dts/dtt — IATA пунктов вылета/прилёта,
         * dtd/ddt — дат в виде dd.MM.yyyy, tripType=1 — «туда-обратно».
         * Для Москвы подставляется конкретный аэропорт хаба (по умолчанию Внуково).
         */
        fun searchUrl(
            hubIata: String,
            arrivalIata: String,
            depDateIso: String,
            retDateIso: String? = null,
            moscowAirport: String = "VKO",
        ): String {
            val from = if (hubIata == "MOW") moscowAirport else hubIata
            fun ruDate(iso: String): String =
                "${iso.substring(8, 10)}.${iso.substring(5, 7)}.${iso.substring(0, 4)}"
            val sb = StringBuilder(SEARCH_URL)
                .append("?adultsCount=1&childrenCount=0&infantsCount=0")
                .append("&dts=").append(from)
                .append("&dtt=").append(arrivalIata)
                .append("&dtd=").append(ruDate(depDateIso))
                .append("&tripType=1")
            if (retDateIso != null) sb.append("&ddt=").append(ruDate(retDateIso))
            return sb.toString()
        }

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
        /**
         * v2.16: true — depTime подтверждён официальным расписанием (stdLocal);
         * false — depTime не подтверждён и показывать его НЕЛЬЗЯ, потому что
         * поле flightTime в best-offers — это ДЛИТЕЛЬНОСТЬ перелёта, а не
         * время вылета (например «1:30» для Минск→СПб при вылете в 19:40).
         */
        val depTimeVerified: Boolean = false,
        /**
         * v2.17: номер рейса подтверждённого вылета из расписания (DP6859).
         * Нужен, чтобы сопоставить «лучшую цену» из best-offers с конкретным
         * рейсом в многорейсовом дне (Москва→Казань — 3 рейса в день).
         */
        val flightNo: String? = null,
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
            // v2.16: время показываем ТОЛЬКО подтверждённое расписанием — иначе
            // это длительность полёта из best-offers, а не вылет.
            if (depTimeVerified) depTime?.let { sb.append(" · ").append(normalizeTime(it)) }
            // v2.17: номер рейса — чтобы можно было сверить с сайтом Победы.
            flightNo?.let { sb.append(" · ").append(it) }
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

        // v2.16: обогащаем ВСЕ записи расписанием ДО сортировки — иначе у
        // неотсортированных элементов теряется depTime (было видно длительность).
        val enriched = enrichMergedWithTimetable(merged)

        val sorted = enriched.sortedWith(
            compareBy<RoutePrices> { if (it.hubIata == "MOW") 0 else 1 }
                .thenBy { it.cheapest?.price ?: Int.MAX_VALUE }
        )

        FetchResult(sorted, errors.toList())
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
        val entries = coroutineScope {
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
        }
        // v2.16: время вылета обратно — только из расписания (stdLocal),
        // с поправкой на разницу часовых поясов аэропортов.
        val enriched = enrichEntriesWithTimetable(entries, arrivalIata, hubIata)
        enriched.associateBy { it.depDate }
    }

    /**
     * v2.16: подставляет в список тарифов реальное время ВЫЛЕТА из официального
     * расписания для плеча [from]->[to]. Время из stdLocal пересчитывается из
     * местного пояса аэропорта отправления в местный пояс этого же аэропорта —
     * т.е. как есть (вылет показываем по местному времени города вылета).
     */
    private suspend fun enrichEntriesWithTimetable(
        entries: List<PriceEntry>,
        from: String,
        to: String,
    ): List<PriceEntry> {
        if (entries.isEmpty()) return entries
        return entries.map { e ->
            val t = runCatching {
                fetchTimetableDepartureTimeForLeg(from, to, e.depDate)
            }.getOrNull()
            if (t != null) e.copy(depTime = t.first, depTimeVerified = true, flightNo = t.second) else e
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

    /**
     * Кэш расписания: дата ISO -> ("откудаIATA-кудаIATA" -> список рейсов
     * (номер, HH:mm местного вылета), отсортированный по времени).
     * v2.17: в дне может быть НЕСКОЛЬКО рейсов по одной паре аэропортов
     * (Москва→Казань — 3 рейса), поэтому храним все, а не последний.
     */
    private val timetableCache =
        java.util.concurrent.ConcurrentHashMap<String, Map<String, List<Pair<String?, String>>>>()

    /**
     * Кэш часовых поясов аэропортов IATA->UTC offset (минуты), извлечённый из
     * ответа flight-timetable (std vs stdLocal). Нужен для пересчёта времени
     * вылёта «обратно» в местное время города возврата.
     */
    private val airportUtcOffsetMin = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Кэш «не удалось получить расписание на дату» — не долбить API при каждой загрузке. */
    private val timetableMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Возвращает карту «$from-$to» -> время вылета (HH:mm, местное время аэропорта)
     * на дату [dateIso] из официального расписания flypobeda.ru. Пустая карта —
     * если расписание на дату недоступно (например, дата дальше ~3 дней).
     * v2.14: при пустом ответе пробуем соседние даты (API иногда сдвигает окно ±1 день),
     * но берём только рейсы на запрошенную [dateIso].
     */
    suspend fun fetchTimetableDepartureTimes(dateIso: String): Map<String, List<Pair<String?, String>>> =
        withContext(Dispatchers.IO) {
            timetableCache.getOrPut(dateIso) {
                // Дата вне окна расписания? Повторим попытку не раньше чем через 6 часов.
                val missedAt = timetableMisses[dateIso] ?: 0L
                if (System.currentTimeMillis() - missedAt < 6L * 60 * 60 * 1000) return@getOrPut emptyMap()
                val map = runCatching {
                    val json = httpGetObject("$TIMETABLE_API?locale=ru&date=$dateIso")
                    parseTimetableFlights(json, onlyOnDate = null)
                }.getOrDefault(emptyMap())
                val finalMap = if (map.isNotEmpty()) map else {
                    // Соседние даты запроса + фильтр по нужной дате вылета.
                    val d = runCatching { LocalDate.parse(dateIso, isoFmt) }.getOrNull()
                    var acc: Map<String, List<Pair<String?, String>>> = emptyMap()
                    if (d != null) {
                        for (nd in listOf(d.plusDays(1), d.minusDays(1))) {
                            val m = runCatching {
                                val j = httpGetObject("$TIMETABLE_API?locale=ru&date=${nd.format(isoFmt)}")
                                parseTimetableFlights(j, onlyOnDate = dateIso)
                            }.getOrDefault(emptyMap())
                            if (m.isNotEmpty()) { acc = m; break }
                        }
                    }
                    acc
                }
                if (finalMap.isEmpty()) timetableMisses[dateIso] = System.currentTimeMillis()
                finalMap
            }
        }

    /**
     * v2.16: время ВЫЛЕТА (местное, HH:mm) конкретного плеча from->to на дату
     * dateIso из расписания. v2.17: если в дне несколько рейсов — берём САМЫЙ
     * РАННИЙ подтверждённый (стабильно и правдоподобно для тарифа «без багажа»).
     */
    private suspend fun fetchTimetableDepartureTimeForLeg(
        from: String,
        to: String,
        dateIso: String,
    ): Pair<String?, String?>? {
        val table = fetchTimetableDepartureTimes(dateIso)
        if (table.isEmpty()) return null
        val legs = table["$from-$to"]
            ?: MOSCOW_AIRPORTS.firstNotNullOfOrNull { table["${it.iata}-$to"] }
            ?: return null
        val best = legs.minByOrNull { it.second } ?: return null
        return best.second to best.first   // (время, номер рейса)
    }

    /**
     * Разбор ответа flight-timetable; [onlyOnDate] (ISO) — брать только рейсы
     * этого дня вылета. v2.17: храним ВСЕ рейсы пары за день (пара аэропортов
     * может иметь 2–4 рейса: Москва→Казань DP6843 06:55, DP6841 08:00,
     * DP6859 15:55 — старая реализация оставляла последний попавшийся).
     */
    private fun parseTimetableFlights(
        json: org.json.JSONObject,
        onlyOnDate: String?,
    ): Map<String, List<Pair<String?, String>>> {
        val flights = json.optJSONArray("flights") ?: return emptyMap()
        val map = HashMap<String, MutableList<Pair<String?, String>>>()
        for (i in 0 until flights.length()) {
            val f = flights.optJSONObject(i) ?: continue
            val dep = f.optJSONObject("departure") ?: continue
            val arr = f.optJSONObject("arrival") ?: continue
            val from = dep.optString("iata").takeIf { it.isNotBlank() } ?: continue
            val to = arr.optString("iata").takeIf { it.isNotBlank() } ?: continue
            // stdLocal — время вылета по МЕСТНОМУ часовому поясу аэропорта.
            val stdLocal = dep.optString("stdLocal").takeIf { it.isNotBlank() && !it.startsWith("01.01.1900") }
            val std = dep.optString("std").takeIf { it.isNotBlank() && !it.startsWith("01.01.1900") }
            // Запоминаем UTC offset аэропорта вылета (stdLocal - std).
            if (stdLocal != null && std != null) {
                val off = utcOffsetMinutes(std, stdLocal)
                if (off != null) airportUtcOffsetMin.putIfAbsent(from, off)
            }
            val rawTime = (stdLocal ?: std)?.substringAfter('T')?.take(5) ?: continue
            if (onlyOnDate != null) {
                val day = (stdLocal ?: std)?.substringBefore('T')?.take(10)
                    ?.replace('.', '-') ?: continue
                if (day != onlyOnDate) continue
            }
            val h = rawTime.substringBefore(':').toIntOrNull() ?: continue
            val m = rawTime.substringAfter(':').toIntOrNull() ?: continue
            if (h in 0..23 && m in 0..59) {
                val no = f.optString("designator").takeIf { it.isNotBlank() }
                map.getOrPut("$from-$to") { ArrayList() }.add(no to "%02d:%02d".format(h, m))
            }
        }
        return map.mapValues { (_, v) -> v.sortedBy { it.second } }
    }

    /** Разница stdLocal - std в минутах (с поправкой на сутки). */
    private fun utcOffsetMinutes(std: String, stdLocal: String): Int? = runCatching {
        val t1 = std.substringAfter('T').split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        val t2 = stdLocal.substringAfter('T').split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        var diff = t2 - t1
        if (diff > 12 * 60) diff -= 24 * 60
        if (diff < -12 * 60) diff += 24 * 60
        diff
    }.getOrNull()

    /**
     * Подставляет в каждый [PriceEntry] реальное время вылета из расписания
     * (best-effort: даты дальше ~3 дней или отсутствующее направление остаются
     * без времени). Возвращает обновлённый список маршрутов.
     * v2.16: помечает depTimeVerified=true только для подтверждённых расписанием.
     * v2.17: при нескольких рейсах в дне берётся САМЫЙ РАННИЙ (раньше
     * map["from-to"] перезаписывался последним рейсом ответа — поэтому
     * «Москва→Казань 15:55» вместо утреннего DP6843 06:55).
     */
    suspend fun enrichWithDepartureTimes(routes: List<RoutePrices>): List<RoutePrices> {
        val dates = routes.flatMap { r -> r.prices.keys }.distinct()
        if (dates.isEmpty()) return routes
        val tables = dates.map { d -> d to fetchTimetableDepartureTimes(d) }.toMap()
        return routes.map { r ->
            val newPrices = r.prices.mapValues { (date, e) ->
                val legs = tables[date]?.get("${r.hubIata}-${r.arrivalIata}")
                    ?: tables[date]?.let { tbl -> firstNotNulls(tbl, r.arrivalIata) }
                val best = legs?.minByOrNull { it.second }
                if (best != null) e.copy(depTime = best.second, depTimeVerified = true, flightNo = best.first) else e
            }
            r.copy(prices = newPrices)
        }
    }

    /**
     * v2.16: обогащает объединённые направления ДО сортировки (см. fetchPrices).
     */
    private suspend fun enrichMergedWithTimetable(routes: List<RoutePrices>): List<RoutePrices> =
        enrichWithDepartureTimes(routes)

    /** Для московского хаба MOW ищем рейс из любого аэропорта Внуково/Шереметьево/... */
    private fun firstNotNulls(
        table: Map<String, List<Pair<String?, String>>>,
        arrivalIata: String,
    ): List<Pair<String?, String>>? =
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
            // v2.16: поле flightTime в best-offers — это ДЛИТЕЛЬНОСТЬ перелёта
            // («1:30» для Минск→СПб), а НЕ время вылета (реальный вылет 19:40).
            // depTime отсюда больше не подставляем: настоящее время вылёта
            // приходит только из расписания (enrichWithDepartureTimes, stdLocal).
            val entry = PriceEntry(
                depDate = depDate,
                price = price,
                airline = airlineRaw,
                flightTime = rawFlightTime,
                legsCount = airlineRaw?.split(",")?.filter { it.isNotBlank() }?.size ?: 1,
                depTime = null,
                depTimeVerified = false,
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
