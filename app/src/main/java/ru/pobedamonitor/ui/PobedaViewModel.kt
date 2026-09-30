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
import java.time.LocalDate

/** Состояние экрана монитора цен. */
data class UiState(
    val hubsSelected: Set<String> = setOf("MOW", "MSQ"),
    val fromDate: LocalDate = LocalDate.now(),
    val daysCount: Int = 14,
    val isLoading: Boolean = false,
    val routes: List<PobedaRepository.RoutePrices> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastUpdated: String? = null,
) {
    val toDate: LocalDate get() = fromDate.plusDays((daysCount - 1).coerceAtLeast(0).toLong())
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

    fun setDate(date: LocalDate) {
        _state.update { it.copy(fromDate = date) }
    }

    fun setDaysCount(count: Int) {
        _state.update { it.copy(daysCount = count.coerceIn(1, 60)) }
    }

    fun refresh() {
        val s = _state.value
        if (s.isLoading) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errors = emptyList()) }
            try {
                val result = repository.fetchPrices(
                    from = s.fromDate,
                    to = s.toDate,
                    hubs = s.hubsSelected.toList(),
                )
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
