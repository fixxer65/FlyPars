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
    )

    /** Направление + цены по всем запрошенным датам. */
    data class RoutePrices(
        val hubIata: String,
        val arrivalIata: String,
        val arrivalName: String,
        val prices: Map<String, PriceEntry>,   // date -> entry
    ) {
        val cheapest: PriceEntry? get() = prices.values.minByOrNull { it.price }
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
     * Делает по одному запросу на дату (одиночный dates[0] — самый надёжный режим
     * API) параллельно, возвращает карту дата ISO -> цена.
     */
    suspend fun fetchReturnPrices(
        hubIata: String,
        arrivalIata: String,
        dates: List<LocalDate>,
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
            }.awaitAll().filterNotNull().associateBy { it.depDate }
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
            val entry = PriceEntry(
                depDate = depDate,
                price = price,
                airline = minOffer.optString("airline").takeIf { it.isNotBlank() },
                flightTime = minOffer.optString("flightTime").takeIf { it.isNotBlank() },
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
