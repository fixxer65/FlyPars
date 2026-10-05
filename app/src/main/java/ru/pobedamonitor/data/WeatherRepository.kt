package ru.pobedamonitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Погода в точке назначения (город прилёта).
 * Основной источник — Open-Meteo (бесплатный, без ключа, координаты аэропортов заданы ниже).
 * Запасной — wttr.in (IATA-код аэропорта), если Open-Meteo недоступен.
 */
object WeatherRepository {

    data class Weather(
        val tempC: Double,
        val description: String,   // «Ясно», «Дождь», «Облачно», «Снег»...
        val icon: String,          // эмодзи
        val source: String
    )

    /** Координаты аэропортов (lat, lon) для Open-Meteo. */
    private val COORDS: Map<String, Pair<Double, Double>> = mapOf(
        // Юг России / курорты
        "AER" to (43.60 to 39.73),   // Сочи
        "KRR" to (45.03 to 39.17),   // Краснодар
        "MRV" to (44.22 to 43.08),   // Минеральные Воды
        "ASF" to (46.23 to 48.04),   // Астрахань
        "STW" to (44.04 to 43.10),   // Ставрополь
        "GDZ" to (44.58 to 38.01),   // Геленджик
        "CEK" to (55.30 to 61.50),   // Челябинск
        // Поволжье / Центр
        "KZN" to (55.61 to 49.28),   // Казань
        "NBC" to (55.57 to 52.08),   // Набережные Челны
        "CSY" to (55.62 to 46.71),   // Чебоксары
        "IJK" to (56.83 to 53.49),   // Ижевск
        "ULV" to (54.26 to 48.22),   // Ульяновск
        "KUF" to (53.50 to 50.16),   // Самара
        "GSV" to (51.07 to 45.98),   // Саратов
        "VOG" to (48.78 to 44.34),   // Волгоград
        "PEE" to (57.91 to 56.02),   // Пермь
        "KVX" to (58.39 to 49.35),   // Киров
        // Северо-Кавказский ФО
        "MCX" to (42.99 to 47.69),   // Махачкала
        "MGZ" to (43.33 to 45.01),   // Магас
        "NAL" to (43.51 to 43.63),   // Нальчик
        "OGZ" to (43.21 to 44.56),   // Владикавказ
        "IGT" to (43.33 to 45.01),   // Ингушетия
        "LWN" to (40.75 to 43.85),   // Гюмри
        // Урал / Сибирь
        "UFA" to (54.56 to 55.87),   // Уфа
        "SVX" to (56.74 to 60.80),   // Екатеринбург
        "KRO" to (55.28 to 65.35),   // Курган
        "OVB" to (55.01 to 82.65),   // Новосибирск
        "OMS" to (54.96 to 73.31),   // Омск
        "BAX" to (53.35 to 83.76),   // Барнаул
        "TJM" to (57.19 to 65.32),   // Тюмень
        "KJA" to (56.17 to 92.49),   // Красноярск
        "MMK" to (68.79 to 32.76),   // Мурманск
        // Северо-Запад
        "LED" to (59.80 to 30.26),   // Санкт-Петербург
        "KGD" to (54.89 to 20.60),   // Калининград
        // Хабсы
        "MOW" to (55.75 to 37.62),   // Москва
        "MSQ" to (53.88 to 28.03),   // Минск
        // Зарубежье
        "GYD" to (40.47 to 50.05),   // Баку
        "IST" to (41.26 to 28.74),   // Стамбул
        "ESB" to (38.74 to 35.49),   // Кайсери
        "AYT" to (36.90 to 30.79),   // Анталья
        "GZP" to (36.95 to 33.11),   // Газипаша
        "ADB" to (37.25 to 27.93),   // Бодрум
        "DLM" to (36.71 to 28.79),   // Даламан
        "EVN" to (40.15 to 44.40),   // Ереван
        "TBS" to (41.67 to 44.95),   // Тбилиси
        "KUT" to (42.28 to 42.63),   // Кутаиси
        "AUH" to (24.43 to 54.65),   // Абу-Даби
        "DXB" to (25.25 to 55.36),   // Дубай
        "SHJ" to (25.32 to 55.51),   // Шарджа
        "TAS" to (41.26 to 69.28),   // Ташкент
        "FRA" to (41.26 to 69.28),   // Ташкент-Восточный
        "OSS" to (40.61 to 72.79),   // Ош
        "SKD" to (39.70 to 66.96),   // Худжанд
        "ISB" to (33.56 to 73.22),   // Исламабад
        "LHE" to (31.52 to 74.40),   // Лахор
    )

    private val cache = ConcurrentHashMap<String, Weather>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    /** Погода по списку IATA-кодов; возвращается карта iata -> Weather (только успешные). */
    suspend fun fetchWeather(iataCodes: List<String>): Map<String, Weather> =
        withContext(Dispatchers.IO) {
            iataCodes.distinct()
                .map { iata ->
                    async {
                        try {
                            if (!COORDS.containsKey(iata)) return@async null
                            val w = fetchOpenMeteo(iata) ?: fetchWttr(iata)
                            if (w != null) cache[iata] = w
                            w?.let { iata to it }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

    fun cached(iata: String): Weather? = cache[iata]

    private fun fetchOpenMeteo(iata: String): Weather? {
        val (lat, lon) = COORDS[iata] ?: return null
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&timezone=auto"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val cur = json.optJSONObject("current") ?: return null
            val temp = cur.optDouble("temperature_2m", Double.NaN)
            if (temp.isNaN()) return null
            val code = cur.optInt("weather_code", -1)
            val isDay = cur.optInt("is_day", 1) == 1
            val (desc, icon) = describeWmo(code, isDay)
            return Weather(temp, desc, icon, "Open-Meteo")
        }
    }

    private fun fetchWttr(iata: String): Weather? {
        val url = "https://wttr.in/$iata?format=j1"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val curArr = json.optJSONArray("current_condition")
            val cur = curArr?.optJSONObject(0) ?: return null
            val temp = cur.optString("temp_C").toDoubleOrNull() ?: return null
            val rawDesc = cur.optJSONArray("weatherDesc")
                ?.optJSONObject(0)?.optString("value").orEmpty()
            val (desc, icon) = describeWttr(rawDesc)
            return Weather(temp, desc, icon, "wttr.in")
        }
    }

    /** WMO weather code -> человекочитаемое описание + эмодзи. */
    private fun describeWmo(code: Int, isDay: Boolean): Pair<String, String> = when {
        code == 0 -> "Ясно" to (if (isDay) "☀️" else "🌙")
        code == 1 -> "Преимущественно ясно" to (if (isDay) "🌤️" else "🌙")
        code == 2 -> "Переменная облачность" to (if (isDay) "⛅" else "☁️")
        code == 3 -> "Пасмурно" to "☁️"
        code == 45 || code == 48 -> "Туман" to "🌫️"
        code in 51..57 -> if (code >= 56) "Ледяной дождь" to "🌧️" else "Морось" to "🌦️"
        code in 61..67 -> if (code >= 66) "Ледяной дождь" to "🌧️" else "Дождь" to "🌧️"
        code in 71..77 || code == 85 || code == 86 -> "Снег" to "❄️"
        code in 80..82 -> "Дождь" to "🌧️"
        code in 95..99 -> "Гроза" to "⛈️"
        else -> "Облачно" to "☁️"
    }

    /** Описание с wttr.in -> русское слово + эмодзи. */
    private fun describeWttr(raw: String): Pair<String, String> {
        val s = raw.lowercase()
        return when {
            listOf("snow", "sleet", "blizzard", "ice pellet", "thunder with snow", "moderate snow", "heavy snow", "light snow").any { s.contains(it) } &&
                !s.contains("rain") -> "Снег" to "❄️"
            s.contains("thunder") -> "Гроза" to "⛈️"
            s.contains("rain") || s.contains("drizzle") || s.contains("shower") -> "Дождь" to "🌧️"
            s.contains("snow") || s.contains("sleet") -> "Снег" to "❄️"
            s.contains("fog") || s.contains("mist") -> "Туман" to "🌫️"
            s.contains("overcast") -> "Пасмурно" to "☁️"
            s.contains("partly cloudy") || s.contains("cloudy") -> "Облачно" to "⛅"
            s.contains("clear") || s.contains("sunny") -> "Ясно" to "☀️"
            else -> "Облачно" to "☁️"
        }
    }
}
