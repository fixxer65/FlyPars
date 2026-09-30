package ru.pobedamonitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Курс RUB -> BYN (белорусский рубль) для конвертации цен авиабилетов.
 *
 * Источники опрашиваются по порядку, берётся первый доступный:
 *  1) Сбер Банк (Беларусь) — страница курсов валют sber-bank.by (парсинг HTML);
 *  2) Нацбанк РБ (nbrb.by) — официальный регуляторный курс;
 *  3) ЦБ РФ (cbr-xml-daily.ru) — резервный кросс-курс RUB/BYN.
 */
class CurrencyRepository(private val client: OkHttpClient = sharedClient) {

    companion object {
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Разумные границы курса RUB->BYN; вне их значение считается ошибкой парсинга. */
        private const val MIN_RATE = 0.01
        private const val MAX_RATE = 1.0
    }

    data class Rate(val bynPerRub: Double, val source: String, val fetchedAtMs: Long)

    suspend fun fetchRate(): Result<Rate> = withContext(Dispatchers.IO) {
        val attempts = listOf<Pair<String, () -> Double?>>(
            "Сбер Банк" to { rubToBynFromSberBank() },
            "Национальный банк РБ" to { rubToBynFromNbrb() },
            "ЦБ РФ" to { rubToBynFromCbr() },
        )
        for ((name, fetch) in attempts) {
            try {
                val rate = fetch()
                if (rate != null && rate > MIN_RATE && rate < MAX_RATE) {
                    return@withContext Result.success(Rate(rate, name, System.currentTimeMillis()))
                }
            } catch (_: Exception) {
                // пробуем следующий источник
            }
        }
        Result.failure(Exception("Не удалось получить курс RUB/BYN"))
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .header("Accept-Language", "ru,en;q=0.9")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /**
     * Сбер Банк (sber-bank.by/page/currency-exchange-cards): ищем в HTML строки вида
     * «Российский рубль ... 2,85 / 2,93» (покупка/продажа) и берём среднее из двух
     * соседних чисел с плавающей запятой в диапазоне допустимого курса.
     */
    private fun rubToBynFromSberBank(): Double? {
        val html = get("https://www.sber-bank.by/page/currency-exchange-cards")
            ?: get("https://sber-bank.by/page/currency-exchange-cards")
            ?: return null
        // Нормализуем: убираем теги, схлопываем пробелы
        val text = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")
        // Ключевые слова рядом с нужной строкой курса
        val keywordIdx = Regex("(?i)(российск\\w* рубл|руб\\b)").findAll(text)
            .map { it.range.first }
            .toList()
        val numRe = Regex("""(\d{1}[.,]\d{2,4}|\d{2}[.,]\d{2,4})""")
        for (start in keywordIdx) {
            val window = text.substring(start, minOf(text.length, start + 220))
            val nums = numRe.findAll(window).mapNotNull { m ->
                m.value.replace(',', '.').toDoubleOrNull()
            }.filter { it in MIN_RATE..MAX_RATE }.toList()
            if (nums.size >= 2) {
                // покупка/продажа -> средний курс
                return (nums[0] + nums[1]) / 2.0
            }
            if (nums.size == 1) return nums[0]
        }
        return null
    }

    /**
     * НЦБ РБ: official_rates.json содержит курсы всех валют к BYN за дату.
     * Считаем кросс: RUB->BYN = (USD->BYN) / (USD->RUB, курс ЦБ РФ на сегодня).
     */
    private fun rubToBynFromNbrb(): Double? {
        val nbrbJson = get("https://www.nbrb.by/api/exrates/rates?periodicity=0")
            ?: get("https://nbrb.by/API/ExRates/Rates?Periodicity=0")
            ?: return null
        val arr = JSONObject("{\"a\":$nbrbJson}").getJSONArray("a")
        var usdByn: Double? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt("Cur_Abbreviation") == 0) {} // noop, JSON keys vary
            val code = o.optString("cur_abbr", o.optString("Cur_Abbreviation", ""))
            val scale = o.optDouble("cur_scale", o.optDouble("Cur_Scale", 1.0)).let { if (it <= 0) 1.0 else it }
            val buy = o.optDouble("cur_buy", o.optDouble("Cur_Buy", Double.NaN))
            val sell = o.optDouble("cur_sell", o.optDouble("Cur_Sell", Double.NaN))
            val off = o.optDouble("cur_official", o.optDouble("Cur_Official", Double.NaN))
            if (code.equals("USD", ignoreCase = true)) {
                val v = when {
                    !off.isNaN() -> off
                    !buy.isNaN() && !sell.isNaN() -> (buy + sell) / 2.0
                    !buy.isNaN() -> buy
                    !sell.isNaN() -> sell
                    else -> Double.NaN
                }
                if (!v.isNaN()) usdByn = v / scale
            }
        }
        if (usdByn == null) return null
        // USD/RUB от ЦБ РФ
        val cbrJson = get("https://www.cbr-xml-daily.ru/daily_json.js") ?: return null
        val usdRub = JSONObject(cbrJson).getJSONObject("Valute").getJSONObject("USD").getDouble("Value")
        if (usdRub <= 0) return null
        return usdByn / usdRub
    }

    /** ЦБ РФ: готовый кросс-курс BYN ( Value = рублей за 1 BYN ) -> переворачиваем. */
    private fun rubToBynFromCbr(): Double? {
        val json = get("https://www.cbr-xml-daily.ru/daily_json.js") ?: return null
        val bynObj = JSONObject(json).getJSONObject("Valute").optJSONObject("BYN") ?: return null
        val rubPerByn = bynObj.getDouble("Value")
        if (rubPerByn <= 0) return null
        return 1.0 / rubPerByn
    }
}
