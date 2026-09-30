package ru.pobedamonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val weekendDays: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    // --- /режим «выходных» ---
    val isLoading: Boolean = false,
    val routes: List<PobedaRepository.RoutePrices> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastUpdated: String? = null,
) {
    val toDate: LocalDate get() = fromDate.plusDays((daysCount - 1).coerceAtLeast(0).toLong())

    /**
     * Даты-вылеты в режиме «выходных»: все дни месяца [month], попадающие в
     * выбранные дни недели, плюс захват «хвостов» недель, переходящих в
     * следующий месяц (например, последняя пятница декабря, а воскресенье —
     * уже 1 января).
     */
    fun weekendDepartureDates(): List<LocalDate> {
        if (weekendDays.isEmpty()) return emptyList()
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        // Стартуем с понедельника недели, содержащей 1-е число,
        // заканчиваем воскресеньем недели, содержащей последнее число.
        val rangeStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rangeEnd = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val result = mutableListOf<LocalDate>()
        var d = rangeStart
        while (d <= rangeEnd) {
            if (d.dayOfWeek in weekendDays) result.add(d)
            d = d.plusDays(1)
        }
        return result
    }
}

class PobedaViewModel : ViewModel() {

    private val repository = PobedaRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fetchJob: Job? = null

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
            val sel = s.weekendDays.toMutableSet()
            if (!sel.remove(day)) sel.add(day)
            s.copy(weekendDays = if (sel.isEmpty()) setOf(day) else sel)
        }
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
                    repository.fetchPrices(
                        from = weekends.first(),
                        to = weekends.last(),
                        hubs = s.hubsSelected.toList(),
                        onlyDates = weekends.toSet(),
                    )
                } else {
                    repository.fetchPrices(
                        from = s.fromDate,
                        to = s.toDate,
                        hubs = s.hubsSelected.toList(),
                    )
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        routes = result.routes,
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
        refresh()
    }
}
