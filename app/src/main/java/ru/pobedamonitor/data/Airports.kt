package ru.pobedamonitor.data

/**
 * Справочник аэропортов прилёта для фильтра «Куда».
 * Составлен по направлениям полётов «Победы» (site-api.flypobeda.ru).
 */
object Airports {

    val ALL: List<Airport> = listOf(
        // Юг России / курорты
        Airport("AER", "Сочи"),
        Airport("KRR", "Краснодар"),
        Airport("MRV", "Минеральные Воды"),
        Airport("ASF", "Астрахань"),
        Airport("STW", "Ставрополь"),
        Airport("GDZ", "Геленджик"),
        Airport("CEK", "Челябинск"),
        // Поволжье / Центр
        Airport("KZN", "Казань"),
        Airport("NBC", "Набережные Челны"),
        Airport("CSY", "Чебоксары"),
        Airport("IJK", "Ижевск"),
        Airport("ULV", "Ульяновск"),
        Airport("KUF", "Самара"),
        Airport("GSV", "Саратов"),
        Airport("VOG", "Волгоград"),
        Airport("PEE", "Пермь"),
        Airport("KVX", "Киров"),
        // Северо-Кавказский ФО
        Airport("MCX", "Махачкала"),
        Airport("MGZ", "Магас"),
        Airport("NAL", "Нальчик"),
        Airport("OGZ", "Владикавказ"),
        Airport("IGT", "Ингушетия (Магас)"),
        Airport("LWN", "Гюмри (Ленинакан)"),
        // Урал / Сибирь
        Airport("UFA", "Уфа"),
        Airport("SVX", "Екатеринбург"),
        Airport("KRO", "Курган"),
        Airport("OVB", "Новосибирск"),
        Airport("OMS", "Омск"),
        Airport("BAX", "Барнаул"),
        Airport("TJM", "Тюмень"),
        Airport("KJA", "Красноярск"),
        Airport("MMK", "Мурманск"),
        // Северо-Запад
        Airport("LED", "Санкт-Петербург"),
        Airport("KGD", "Калининград"),
        // Зарубежье
        Airport("MSQ", "Минск"),
        Airport("GYD", "Баку"),
        Airport("IST", "Стамбул"),
        Airport("ESB", "Кайсери"),
        Airport("AYT", "Анталья"),
        Airport("GZP", "Газипаша (Алания)"),
        Airport("ADB", "Бодрум"),
        Airport("DLM", "Даламан"),
        Airport("EVN", "Ереван"),
        Airport("TBS", "Тбилиси"),
        Airport("KUT", "Кутаиси"),
        Airport("AUH", "Абу-Даби"),
        Airport("DXB", "Дубай"),
        Airport("SHJ", "Шарджа"),
        Airport("TAS", "Ташкент"),
        Airport("FRA", "Сокрут (Ташкент-Восточный)"),
        Airport("OSS", "Ош"),
        Airport("SKD", "Худжанд"),
        Airport("ISB", "Исламабад"),
        Airport("LHE", "Лахор"),
    ).sortedBy { it.name }

    /** Возвращает имя города по IATA-коду (или сам код, если неизвестен). */
    fun nameOf(iata: String): String =
        ALL.firstOrNull { it.iata == iata }?.name ?: iata
}
