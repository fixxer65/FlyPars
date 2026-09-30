package ru.pobedamonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.pobedamonitor.data.CurrencyRepository
import ru.pobedamonitor.data.PobedaRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Режимы поиска. */
enum class SearchMode { ALL_DAYS, WEEKENDS }

/** Состояние экрана монитора цен. */
data class UiState(
    val hubsSelected: Set<String> = setOf("MOW", "MSQ"),
    val mode: SearchMode = SearchMode.ALL_DAYS,
    val fromDate: LocalDate = LocalDate.now(),
    val daysCount: Int = 14,
    // --- режим «выходных» ---
    val month: YearMonth = YearMonth.now().plusMonths(1),
    /** Дни недели вылета «туда» (например, Чт/Пт). */
    val outboundDays: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    /** Включён ли поиск обратных билетов. */
    val returnEnabled: Boolean = false,
    /** Дни недели обратного вылета (например, Вс/Пн). */
    val returnDays: Set<DayOfWeek> = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
    /** Обратные цены: "hub-arrival" -> (дата ISO -> цена). */
    val returnPrices: Map<String, Map<String, PobedaRepository.PriceEntry>> = emptyMap(),
    // --- /режим «выходных» ---
    /** Фильтр «Куда»: пустой список = все направления. */
    val destinationsSelected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val routes: List<PobedaRepository.RoutePrices> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastUpdated: String? = null,
    /** Курс RUB -> BYN (для конвертации цен), null = ещё не загружен. */
    val bynPerRub: Double? = null,
    /** Название источника курса (например, «Сбер Банк»). */
    val rateSource: String? = null,
    /** Ошибка загрузки курса (показывается в шапке, если курс недоступен). */
    val rateError: String? = null,
) {
    val toDate: LocalDate get() = fromDate.plusDays((daysCount - 1).coerceAtLeast(0).toLong())

    /**
     * Даты-вылеты в режиме «выходных»: все дни месяца [month], попадающие в
     * выбранные дни недели [outboundDays], плюс захват «хвостов» недель, переходящих в
     * следующий месяц (например, последняя пятница декабря, а воскресенье —
     * уже 1 января).
     */
    fun weekendDepartureDates(): List<LocalDate> {
        if (outboundDays.isEmpty()) return emptyList()
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        // Стартуем с понедельника недели, содержащей 1-е число,
        // заканчиваем воскресеньем недели, содержащей последнее число.
        val rangeStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rangeEnd = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val result = mutableListOf<LocalDate>()
        var d = rangeStart
        while (d <= rangeEnd) {
            if (d.dayOfWeek in outboundDays) result.add(d)
            d = d.plusDays(1)
        }
        return result
    }

    /**
     * Возможные даты возврата для вылета [departure]: выбранные дни недели
     * [returnDays] через 2..9 дней после вылета (охватывают соседний месяц).
     */
    fun returnDatesFor(departure: LocalDate): List<LocalDate> {
        if (!returnEnabled || returnDays.isEmpty()) return emptyList()
        return (2L..9L).map { departure.plusDays(it) }
            .filter { it.dayOfWeek in returnDays }
    }

    fun keyFor(route: PobedaRepository.RoutePrices): String =
        "${route.hubIata}-${route.arrivalIata}"

    /** Направления, отфильтрованные по выбранному аэропорту прилёта («Куда»). */
    fun visibleRoutes(): List<PobedaRepository.RoutePrices> =
        if (destinationsSelected.isEmpty()) routes
        else routes.filter { it.arrivalIata in destinationsSelected }

    /** Пара «туда/обратно» для одной карточки: вылет из хаба в город назначения. */
    data class TripPair(
        val route: PobedaRepository.RoutePrices,
        val legs: List<Leg>,
    ) {
        data class Leg(
            val outboundDate: LocalDate,
            val outbound: PobedaRepository.PriceEntry?,
            /** Дата возврата -> (запись цены, true если дата подобрана ближайшая). */
            val returns: List<Pair<LocalDate, Pair<PobedaRepository.PriceEntry, Boolean>>>,
        )

        /** Все комбинации «вылет + возврат» с ценой каждой пары. */
        data class Combo(
            val depDate: LocalDate,
            val retDate: LocalDate,
            val approxReturn: Boolean,
            val outboundPrice: Int,
            val returnPrice: Int,
        ) {
            val total: Int get() = outboundPrice + returnPrice
        }

        /** Полный список пар туда+обратно для этого направления (все даты × все возвраты). */
        val combos: List<Combo>
            get() = legs.flatMap { leg ->
                val o = leg.outbound ?: return@flatMap emptyList()
                leg.returns.map { (rd, pe) ->
                    Combo(leg.outboundDate, rd, pe.second, o.price, pe.first.price)
                }
            }.sortedBy { it.total }

        /** Минимальная суммарная цена пары туда+обратно по всем строкам карточки. */
        val cheapestTotal: Int?
            get() = combos.minOfOrNull { it.total }
    }

    /**
     * Все возможные даты возврата для списка вылетов: выбранные дни недели
     * [returnDays] через 2..9 дней после каждого вылета (охватывают соседний месяц).
     */
    fun allReturnDates(departureDates: List<LocalDate>): Set<LocalDate> {
        if (!returnEnabled || returnDays.isEmpty()) return emptySet()
        return departureDates.flatMap { dep ->
            (2L..9L).map { dep.plusDays(it) }.filter { it.dayOfWeek in returnDays }
        }.toSet()
    }

    /**
     * Возвращает цену из словаря [prices] на точную дату; если точной даты нет
     * (API Pobeda отдаёт цены не на все дни), берётся ближайшая дата с ценой
     * в пределах ±3 дней. Второе значение — true, если дата была подставлена.
     */
    private fun nearestPrice(
        prices: Map<String, PobedaRepository.PriceEntry>,
        date: LocalDate,
    ): Pair<PobedaRepository.PriceEntry, Boolean>? {
        prices[date.toString()]?.let { return it to false }
        var best: Pair<PobedaRepository.PriceEntry, Boolean>? = null
        var bestDist = Int.MAX_VALUE
        for (offset in -3..3) {
            if (offset == 0) continue
            val iso = date.plusDays(offset.toLong()).toString()
            val e = prices[iso] ?: continue
            if (kotlin.math.abs(offset) < bestDist) {
                bestDist = kotlin.math.abs(offset)
                best = e to true
            }
        }
        return best
    }

    /** Собирает пары «туда + обратно» по датам вылета (режим выходных). */
    fun tripPairs(departureDates: List<LocalDate>): List<TripPair> {
        return visibleRoutes().map { route ->
            val ret = returnPrices[keyFor(route)] ?: emptyMap()
            val legs = departureDates.mapNotNull { dep ->
                val out = route.prices[dep.toString()]
                // Если тарифа на саму дату вылета нет — пробуем ближайшую дату
                // (±3 дня): сайт может показывать цену, а сетка API — другую дату.
                val outEntry = out ?: nearestPrice(route.prices, dep)?.first
                val returns = if (returnEnabled && returnDays.isNotEmpty()) {
                    (2L..9L)
                        .map { dep.plusDays(it) }
                        .filter { it.dayOfWeek in returnDays }
                        .mapNotNull { rd -> nearestPrice(ret, rd)?.let { rd to it } }
                } else emptyList()
                if (outEntry == null && returns.isEmpty()) return@mapNotNull null
                TripPair.Leg(dep, outEntry, returns)
            }
            TripPair(route, legs)
        }.filter { it.legs.isNotEmpty() }
    }
}

class PobedaViewModel : ViewModel() {

    private val repository = PobedaRepository()
    private val currencyRepository = CurrencyRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var rateJob: Job? = null

    /** Загружает курс RUB->BYN (Сбер Банк -> НЦБ РБ -> ЦБ РФ). */
    fun refreshRate() {
        if (rateJob?.isActive == true) return
        rateJob = viewModelScope.launch {
            val res = currencyRepository.fetchRate()
            _state.update { s ->
                res.fold(
                    onSuccess = { r -> s.copy(bynPerRub = r.bynPerRub, rateSource = r.source, rateError = null) },
                    onFailure = { e -> s.copy(rateError = e.message ?: "Курс недоступен") },
                )
            }
        }
    }

    fun toggleHub(iata: String) {
        _state.update { s ->
            val sel = s.hubsSelected.toMutableSet()
            if (!sel.remove(iata)) sel.add(iata)
            // хотя бы один хаб должен быть выбран
            s.copy(hubsSelected = if (sel.isEmpty()) setOf(iata) else sel)
        }
    }

    fun setMode(mode: SearchMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(fromDate = date) }
    }

    fun setDaysCount(count: Int) {
        _state.update { it.copy(daysCount = count.coerceIn(1, 60)) }
    }

    fun setMonth(m: YearMonth) {
        _state.update { it.copy(month = m) }
    }

    fun shiftMonth(delta: Long) {
        _state.update { it.copy(month = it.month.plusMonths(delta)) }
    }

    fun toggleWeekendDay(day: DayOfWeek) {
        _state.update { s ->
            val sel = s.outboundDays.toMutableSet()
            if (!sel.remove(day)) sel.add(day)
            s.copy(outboundDays = if (sel.isEmpty()) setOf(day) else sel)
        }
    }

    fun toggleReturnEnabled(enabled: Boolean) {
        _state.update { it.copy(returnEnabled = enabled) }
    }

    fun toggleReturnDay(day: DayOfWeek) {
        _state.update { s ->
            val sel = s.returnDays.toMutableSet()
            if (!sel.remove(day)) sel.add(day)
            s.copy(returnDays = if (sel.isEmpty()) setOf(day) else sel)
        }
    }

    /** Выбор аэропорта(ов) прилёта; пустое множество = все направления. */
    fun toggleDestination(iata: String) {
        _state.update { s ->
            val sel = s.destinationsSelected.toMutableSet()
            if (!sel.remove(iata)) sel.add(iata)
            s.copy(destinationsSelected = sel)
        }
    }

    fun clearDestinations() {
        _state.update { it.copy(destinationsSelected = emptySet()) }
    }

    fun refresh() {
        val s = _state.value
        if (s.isLoading) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errors = emptyList()) }
            try {
                val weekends = if (s.mode == SearchMode.WEEKENDS) s.weekendDepartureDates() else emptyList()
                val result = if (s.mode == SearchMode.WEEKENDS && weekends.isNotEmpty()) {
                    // Запрашиваем и даты вылета, и все возможные даты возврата —
                    // иначе обратные цены просто не придут из API.
                    val allDates = (weekends + s.allReturnDates(weekends)).toSortedSet()
                    repository.fetchPrices(
                        from = allDates.first(),
                        to = allDates.last(),
                        hubs = s.hubsSelected.toList(),
                        onlyDates = allDates,
                    )
                } else {
                    repository.fetchPrices(
                        from = s.fromDate,
                        to = s.toDate,
                        hubs = s.hubsSelected.toList(),
                    )
                }

                // Обратные билеты (режим «выходных» с включённым возвратом):
                // для каждого направления собираем цены возврата в выбранные дни.
                var returnPrices = emptyMap<String, Map<String, PobedaRepository.PriceEntry>>()
                if (s.mode == SearchMode.WEEKENDS && s.returnEnabled && result.routes.isNotEmpty()) {
                    val retDates = s.allReturnDates(weekends).sorted()
                    if (retDates.isNotEmpty()) {
                        returnPrices = coroutineScope {
                            result.routes.map { route ->
                                async {
                                    s.keyFor(route) to repository.fetchReturnPrices(
                                        hubIata = route.hubIata,
                                        arrivalIata = route.arrivalIata,
                                        dates = retDates,
                                    )
                                }
                            }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
                        }
                    }
                }

                _state.update {
                    it.copy(
                        isLoading = false,
                        routes = result.routes,
                        returnPrices = returnPrices,
                        errors = result.errors,
                        lastUpdated = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, errors = it.errors + (e.message ?: "Ошибка сети"))
                }
            }
        }
    }

    init {
        refreshRate()
        refresh()
    }
}
