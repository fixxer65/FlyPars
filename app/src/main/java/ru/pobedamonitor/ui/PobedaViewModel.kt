package ru.pobedamonitor.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import ru.pobedamonitor.data.WeatherRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

/** Режимы поиска. */
enum class SearchMode { ALL_DAYS, WEEKENDS }

/** Сортировка результатов по цене. */
enum class SortOrder { NONE, ASC, DESC }

/** Фильтр списка: все направления / только избранные. */
enum class ListFilter { ALL, FAVORITES }

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
    /** Сортировка карточек по цене: без сортировки / по возрастанию / по убыванию. */
    val sortOrder: SortOrder = SortOrder.NONE,
    /** Фильтр «только избранные». */
    val listFilter: ListFilter = ListFilter.ALL,
    /** Избранные направления (ключи "hub-arrival"), сохраняются между запусками. */
    val favorites: Set<String> = emptySet(),
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
    /** Погода в городах прилёта: IATA -> Weather. */
    val weather: Map<String, WeatherRepository.Weather> = emptyMap(),
    /** Прогноз выгодности по истории цен: "hub-arrival" -> Assessment (v2.3). */
    val assessments: Map<String, ru.pobedamonitor.data.PriceHistoryRepository.Assessment> = emptyMap(),
    /** Уведомления «цена упала» включены (v2.4). */
    val notificationsEnabled: Boolean = false,
    /** Порог падения цены для уведомления, % (v2.4). */
    val dropThresholdPercent: Int = ru.pobedamonitor.data.FavoritesRepository.DEFAULT_DROP_PERCENT,
    /** v2.6: режим порога уведомлений — "percent" или "amount". */
    val notifyMode: String = ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT,
    /** v2.6: порог падения в рублях (для режима "amount"). */
    val dropThresholdAmount: Int = 0,
    /** v2.6: считать цену как сумму «туда и обратно» (иначе — только «туда»). */
    val notifyRoundTrip: Boolean = false,
    /** v2.7: целевые цены по направлениям ("hub-arrival" -> сумма ₽) — уведомление при достижении. */
    val targetPrices: Map<String, Int> = emptyMap(),
    /** v2.10: направления, у которых цель задана на сумму «туда+обратно». */
    val targetRoundTrips: Set<String> = emptySet(),
    /** v2.12: интервал фоновой проверки цен, часов (1 / 6 / 24). */
    val checkIntervalHours: Int = ru.pobedamonitor.data.FavoritesRepository.DEFAULT_CHECK_HOURS,
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

    /** Направления, отфильтрованные по выбранному аэропорту прилёта («Куда»),
     *  по избранности (filter=Favorites) и отсортированные по минимальной цене
     *  согласно [sortOrder]. */
    fun visibleRoutes(): List<PobedaRepository.RoutePrices> {
        var filtered = if (destinationsSelected.isEmpty()) routes
        else routes.filter { it.arrivalIata in destinationsSelected }
        if (listFilter == ListFilter.FAVORITES) {
            filtered = filtered.filter { keyFor(it) in favorites }
        }
        return when (sortOrder) {
            SortOrder.NONE -> filtered
            SortOrder.ASC -> filtered.sortedBy { it.cheapest?.price ?: Int.MAX_VALUE }
            SortOrder.DESC -> filtered.sortedByDescending { it.cheapest?.price ?: -1 }
        }
    }

    /** Избранные направления с их текущими ценами (для виджета). */
    fun favoriteRoutesWithPrices(): List<PobedaRepository.RoutePrices> =
        routes.filter { keyFor(it) in favorites }

    /** Пара «туда/обратно» для одной карточки: вылет из хаба в город назначения. */
    data class TripPair(
        val route: PobedaRepository.RoutePrices,
        val legs: List<Leg>,
        val sortOrder: SortOrder = SortOrder.NONE,
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
            // v2.13: время вылета и прямой/стыковочный для каждого плеча пары
            val outboundDepTime: String? = null,
            val outboundDirect: Boolean = true,
            val returnDepTime: String? = null,
            val returnDirect: Boolean = true,
        ) {
            val total: Int get() = outboundPrice + returnPrice

            /** Подпись плеча «✈ 09 окт · 14:55 · прямой» (v2.13). */
            fun legLabel(
                date: LocalDate,
                depTime: String?,
                direct: Boolean,
                fmt: DateTimeFormatter,
            ): String {
                val d = date.format(fmt).replaceFirstChar { it.uppercase() }
                val t = depTime?.let { " · $it" } ?: ""
                return "$d$t · ${if (direct) "прямой" else "стык."}"
            }
        }

        /** Полный список пар туда+обратно для этого направления (все даты × все возвраты).
         *  Порядок зависит от выбранной сортировки [sortOrder]. */
        val combos: List<Combo>
            get() {
                val list = legs.flatMap { leg ->
                    val o = leg.outbound ?: return@flatMap emptyList()
                    leg.returns.map { (rd, pe) ->
                        Combo(
                            depDate = leg.outboundDate,
                            retDate = rd,
                            approxReturn = pe.second,
                            outboundPrice = o.price,
                            returnPrice = pe.first.price,
                            outboundDepTime = o.depTime,
                            outboundDirect = o.isDirect,
                            returnDepTime = pe.first.depTime,
                            returnDirect = pe.first.isDirect,
                        )
                    }
                }
                return when (sortOrder) {
                    SortOrder.NONE -> list.sortedBy { it.depDate }
                    SortOrder.ASC -> list.sortedBy { it.total }
                    SortOrder.DESC -> list.sortedByDescending { it.total }
                }
            }

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

    /** Собирает пары «туда + обратно» по датам вылета (режим выходных).
     *  Карточки сортируются по минимальной сумме пары согласно [sortOrder]. */
    fun tripPairs(departureDates: List<LocalDate>): List<TripPair> {
        val pairs = visibleRoutes().map { route ->
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
            TripPair(route, legs, sortOrder)
        }.filter { it.legs.isNotEmpty() }
        return when (sortOrder) {
            SortOrder.NONE -> pairs
            SortOrder.ASC -> pairs.sortedBy { it.cheapestTotal ?: Int.MAX_VALUE }
            SortOrder.DESC -> pairs.sortedByDescending { it.cheapestTotal ?: -1 }
        }
    }

    /** Лучшая суммарная цена «туда+обратно» среди всех направлений (для шапки результатов). */
    fun bestPairTotal(departureDates: List<LocalDate>): Int? =
        tripPairs(departureDates).mapNotNull { it.cheapestTotal }.minOrNull()
}

class PobedaViewModel(private val appContext: Context) : ViewModel() {

    private val repository = PobedaRepository()
    private val currencyRepository = CurrencyRepository()
    private val favoritesRepository = ru.pobedamonitor.data.FavoritesRepository(appContext)
    private val historyRepository = ru.pobedamonitor.data.PriceHistoryRepository(appContext)
    private val dropNotifier = ru.pobedamonitor.notify.PriceDropNotifier(appContext)

    init {
        // v2.8: если уведомления или цели включены — убеждаемся, что фоновая
        // проверка цен стоит (переживает обновления приложения).
        if (favoritesRepository.notificationsEnabled || favoritesRepository.allTargetPrices().isNotEmpty()) {
            ru.pobedamonitor.notify.PriceCheckWorker.schedule(appContext)
        }
    }

    private val _state = MutableStateFlow(
        UiState(
            favorites = favoritesRepository.load(),
            notificationsEnabled = favoritesRepository.notificationsEnabled,
            dropThresholdPercent = favoritesRepository.dropThresholdPercent,
            notifyMode = favoritesRepository.notifyMode,
            dropThresholdAmount = favoritesRepository.dropThresholdAmount,
            notifyRoundTrip = favoritesRepository.notifyRoundTrip,
            targetPrices = favoritesRepository.allTargetPrices(),
            targetRoundTrips = favoritesRepository.allTargetRoundTrips(),
            checkIntervalHours = favoritesRepository.checkIntervalHours,
        )
    )
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

    /** Циклическое переключение сортировки: без → по возрастанию → по убыванию. */
    fun cycleSortOrder() {
        _state.update { s ->
            val next = when (s.sortOrder) {
                SortOrder.NONE -> SortOrder.ASC
                SortOrder.ASC -> SortOrder.DESC
                SortOrder.DESC -> SortOrder.NONE
            }
            s.copy(sortOrder = next)
        }
    }

    fun setSortOrder(order: SortOrder) {
        _state.update { it.copy(sortOrder = order) }
    }

    /** Переключить фильтр «только избранные». */
    fun setListFilter(filter: ListFilter) {
        _state.update { it.copy(listFilter = filter) }
    }

    /** Включить/выключить уведомления «цена упала» (v2.4). */
    fun setNotificationsEnabled(enabled: Boolean) {
        favoritesRepository.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
        // v2.8: включаем/выключаем ежедневную фоновую проверку цен.
        if (enabled || favoritesRepository.allTargetPrices().isNotEmpty()) {
            ru.pobedamonitor.notify.PriceCheckWorker.schedule(appContext)
        } else {
            ru.pobedamonitor.notify.PriceCheckWorker.cancel(appContext)
        }
    }

    /** Изменить порог падения цены для уведомлений, % (v2.4). */
    fun setDropThreshold(percent: Int) {
        val v = percent.coerceIn(1, 90)
        favoritesRepository.dropThresholdPercent = v
        _state.update { it.copy(dropThresholdPercent = v) }
    }

    /** v2.6: режим порога уведомлений — MODE_PERCENT или MODE_AMOUNT. */
    fun setNotifyMode(mode: String) {
        favoritesRepository.notifyMode = mode
        _state.update { it.copy(notifyMode = mode) }
    }

    /** v2.6: порог падения цены в рублях (режим «сумма»). */
    fun setDropAmountRub(amount: Int) {
        val v = amount.coerceIn(0, 1_000_000)
        favoritesRepository.dropThresholdAmount = v
        _state.update { it.copy(dropThresholdAmount = v) }
    }

    /** v2.6: считать ли цену как сумму «туда и обратно». */
    fun setNotifyRoundTrip(enabled: Boolean) {
        favoritesRepository.notifyRoundTrip = enabled
        _state.update { it.copy(notifyRoundTrip = enabled) }
    }

    /** v2.12: выбрать интервал фоновой проверки цен (1 / 6 / 24 часа). */
    fun setCheckInterval(hours: Int) {
        val h = hours.coerceIn(
            ru.pobedamonitor.data.FavoritesRepository.MIN_CHECK_HOURS,
            ru.pobedamonitor.data.FavoritesRepository.MAX_CHECK_HOURS,
        )
        favoritesRepository.checkIntervalHours = h
        _state.update { it.copy(checkIntervalHours = h) }
        // Пересоздаём фоновое задание с новым интервалом, если оно активно.
        if (favoritesRepository.notificationsEnabled || favoritesRepository.allTargetPrices().isNotEmpty()) {
            ru.pobedamonitor.notify.PriceCheckWorker.schedule(appContext, h)
        }
    }

    /** v2.7: задать/снять целевую цену направления (0 = снять). v2.10: цель может быть на сумму «туда+обратно». */
    fun setTargetPrice(code: String, priceRub: Int, roundTrip: Boolean = false) {
        val v = priceRub.coerceIn(0, 1_000_000)
        favoritesRepository.setTargetPrice(code, v, roundTrip)
        _state.update { s ->
            val map = s.targetPrices.toMutableMap()
            val rts = s.targetRoundTrips.toMutableSet()
            if (v > 0) { map[code] = v; if (roundTrip) rts += code else rts -= code }
            else { map.remove(code); rts -= code }
            s.copy(targetPrices = map, targetRoundTrips = rts)
        }
        // v2.8: цели проверяются и в фоне — ставим ежедневную задачу, пока есть хотя бы одна.
        if (v > 0 || favoritesRepository.notificationsEnabled) {
            ru.pobedamonitor.notify.PriceCheckWorker.schedule(appContext)
        } else if (favoritesRepository.allTargetPrices().isEmpty()) {
            ru.pobedamonitor.notify.PriceCheckWorker.cancel(appContext)
        }
    }

    /** v2.12: имя города прилёта по ключу "hub-arrival" (из загруженных направлений, иначе IATA). */
    fun arrivalNameFor(code: String): String =
        _state.value.routes.firstOrNull { _state.value.keyFor(it) == code }
            ?.arrivalName?.ifBlank { null }
            ?: code.substringAfter('-')

    /** v2.12: последняя известная цена направления (для экрана «Мои цели», когда список не загружен). */
    fun lastKnownPrice(code: String): Int? = favoritesRepository.getLastKnownPrice(code)

    /** Добавить/убрать направление из избранного, сохранить локально. */
    fun toggleFavorite(code: String) {
        val newSet = favoritesRepository.toggle(code)
        _state.update { it.copy(favorites = newSet) }
        updateWidget()
    }

    /** Сохраняет снимок избранных цен в prefs и обновляет домашний виджет. */
    private fun updateWidget() {
        val s = _state.value
        val entries = s.favoriteRoutesWithPrices().map { r ->
            ru.pobedamonitor.data.FavoritesRepository.WidgetEntry(
                name = r.arrivalName.ifBlank { r.arrivalIata },
                priceRub = r.cheapest?.price,
            )
        }
        favoritesRepository.saveSnapshot(entries)
        ru.pobedamonitor.widget.PriceWidget.update(appContext)
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

                // Погода в городах прилёта (параллельно загрузке цен, не блокирует UI).
                val destCodes = result.routes.map { it.arrivalIata }.distinct()
                if (destCodes.isNotEmpty()) {
                    val w = WeatherRepository.fetchWeather(destCodes)
                    if (w.isNotEmpty()) {
                        _state.update { it.copy(weather = it.weather + w) }
                    }
                }

                // Сохраняем сегодняшние минимальные цены в историю и строим прогноз (v2.3).
                runCatching {
                    val todayPrices = result.routes.mapNotNull { r ->
                        r.cheapest?.price?.let { s.keyFor(r) to it }
                    }.toMap()
                    historyRepository.recordAll(todayPrices)
                    val assessments = todayPrices.mapNotNull { (code, price) ->
                        historyRepository.assess(code, price)?.let { code to it }
                    }.toMap()
                    _state.update { it.copy(assessments = assessments) }
                }

                // Обновляем домашний виджет избранных направлений.
                updateWidget()

                // Уведомления «цена упала» по избранным направлениям (v2.4, v2.6: сумма ₽ / туда+обратно).
                runCatching {
                    dropNotifier.onPricesLoaded(result.routes, { s.keyFor(it) }, returnPrices)
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

    /** История цен одного направления (v2.5): наблюдения «дата -> мин. цена», старые -> новые. */
    fun historyFor(hubIata: String, arrivalIata: String): List<Pair<LocalDate, Int>> =
        historyRepository.historyFor("$hubIata-$arrivalIata")

    /** Экспорт всей накопленной истории в CSV-файл (v2.5). null — если истории ещё нет. */
    fun exportHistoryCsv(): java.io.File? =
        ru.pobedamonitor.data.CsvExporter(appContext).export(historyRepository.loadAll())

    companion object {
        /** Фабрика: ViewModel получает ApplicationContext для работы с prefs/виджетом. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PobedaViewModel(context.applicationContext) as T
            }
    }
}
