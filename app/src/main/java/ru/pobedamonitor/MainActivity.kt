package ru.pobedamonitor

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.pobedamonitor.data.Airport
import ru.pobedamonitor.data.Airports
import ru.pobedamonitor.data.PobedaRepository
import ru.pobedamonitor.ui.PobedaViewModel
import ru.pobedamonitor.ui.SearchMode
import ru.pobedamonitor.ui.UiState
import java.time.DayOfWeek
import java.time.LocalDate

import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PobedaColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }
}

private val PobedaColors
    get() = lightColorScheme(
        primary = Color(0xFFE4232B),        // фирменный красный «Победы»
        onPrimary = Color.White,
        secondary = Color(0xFF1B1B1F),
        background = Color(0xFFF6F6F8),
        surface = Color.White,
        errorContainer = Color(0xFFFFE5E5),
    )

@Composable
fun MainScreen(vm: PobedaViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        TopAppBar(state = state, onRefresh = vm::refresh)
        FilterRow(state = state, vm = vm)
        DestinationPicker(state = state, vm = vm)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        val visible = state.visibleRoutes()
        when {
            state.isLoading && visible.isEmpty() -> LoadingBlock()
            visible.isEmpty() && !state.isLoading -> EmptyBlock(state)
            else -> RouteList(state, visible)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBar(state: UiState, onRefresh: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Авиабилеты Победа", fontWeight = FontWeight.Bold)
                state.lastUpdated?.let {
                    Text(
                        "Обновлено: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * Фильтр «Куда»: выпадающий список всех аэропортов прилёта с поиском
 * и мультивыбором. Пустой выбор = все направления.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationPicker(state: UiState, vm: PobedaViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Все известные направления + те, что реально присутствуют в ответах API
    val knownCodes = Airports.ALL.map { it.iata }.toSet()
    val extra = state.routes
        .filter { it.arrivalIata !in knownCodes && it.arrivalIata.isNotBlank() }
        .map { Airport(it.arrivalIata, it.arrivalName.ifBlank { it.arrivalIata }) }
    val allOptions = (Airports.ALL + extra).distinctBy { it.iata }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    val filtered = if (query.isBlank()) allOptions else allOptions.filter {
        it.name.contains(query.trim(), ignoreCase = true) ||
            it.iata.contains(query.trim(), ignoreCase = true)
    }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.DateRange, // заглушка не нужна — используем стрелку
                contentDescription = null,
                modifier = Modifier.size(0.dp),
            )
            Text(
                text = when {
                    state.destinationsSelected.isEmpty() -> "Куда: все направления"
                    state.destinationsSelected.size == 1 -> {
                        val code = state.destinationsSelected.first()
                        "Куда: ${Airports.nameOf(code)} ($code)"
                    }
                    else -> "Куда: выбрано ${state.destinationsSelected.size}"
                },
                maxLines = 1,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text("▾")
            if (state.destinationsSelected.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::clearDestinations) { Text("Сбросить ✕") }
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Поиск
            DropdownMenuItem(
                text = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск города или кода…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                onClick = {},
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        if (state.destinationsSelected.isEmpty()) "✓ Все направления"
                        else "Все направления",
                        fontWeight = if (state.destinationsSelected.isEmpty())
                            FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = { vm.clearDestinations() },
            )
            filtered.forEach { ap ->
                val checked = ap.iata in state.destinationsSelected
                DropdownMenuItem(
                    leadingIcon = {
                        Checkbox(checked = checked, onCheckedChange = null)
                    },
                    text = { Text("${ap.name} (${ap.iata})") },
                    onClick = { vm.toggleDestination(ap.iata) },
                )
            }
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text("Ничего не найдено") }, onClick = {})
            }
        }
    }
}

@Composable
private fun FilterRow(state: UiState, vm: PobedaViewModel) {
    Column(Modifier.fillMaxWidth()) {
        // Переключатель режима поиска
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            SegmentedButton(
                selected = state.mode == SearchMode.ALL_DAYS,
                onClick = { vm.setMode(SearchMode.ALL_DAYS); vm.refresh() },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Все дни") }
            SegmentedButton(
                selected = state.mode == SearchMode.WEEKENDS,
                onClick = { vm.setMode(SearchMode.WEEKENDS); vm.refresh() },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Выходные в месяце") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Переключатели хабов
            PobedaRepository.HUBS.forEach { hub ->
                val selected = hub.iata in state.hubsSelected
                FilterChip(
                    selected = selected,
                    onClick = { vm.toggleHub(hub.iata); vm.refresh() },
                    label = { Text(hub.name) },
                )
            }
        }

        if (state.mode == SearchMode.ALL_DAYS) {
            AllDaysControls(state, vm)
        } else {
            WeekendsControls(state, vm)
        }
    }
}

/** Обычный режим: дата начала + глубина периода. */
@Composable
private fun AllDaysControls(state: UiState, vm: PobedaViewModel) {
    val context = LocalContext.current
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", RU) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = {
            val d = state.fromDate
            DatePickerDialog(
                context,
                { _, y, m, day ->
                    vm.setDate(LocalDate.of(y, m + 1, day))
                    vm.refresh()
                },
                d.year, d.monthValue - 1, d.dayOfMonth,
            ).apply {
                datePicker.minDate = System.currentTimeMillis() - 24L * 3600 * 1000
            }.show()
        }) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(state.fromDate.format(dateFmt))
        }

        // Выбор глубины периода
        listOf(7, 14, 30).forEach { days ->
            FilterChip(
                selected = state.daysCount == days,
                onClick = { vm.setDaysCount(days); vm.refresh() },
                label = { Text("${days} дн.") },
            )
        }
    }
}

/** Режим «выходных»: месяц + дни вылета «туда» + опция обратных билетов. */
@Composable
private fun WeekendsControls(state: UiState, vm: PobedaViewModel) {
    val monthFmt = remember {
        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", RU)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { vm.shiftMonth(-1); vm.refresh() }) {
                Text("‹", fontSize = MaterialTheme.typography.headlineMedium.fontSize)
            }
            AssistChip(
                onClick = { vm.setMonth(java.time.YearMonth.now().plusMonths(1)); vm.refresh() },
                label = {
                    Text(
                        state.month.atDay(1).format(monthFmt)
                            .replaceFirstChar { it.uppercase(RU) },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
            IconButton(onClick = { vm.shiftMonth(+1); vm.refresh() }) {
                Text("›", fontSize = MaterialTheme.typography.headlineMedium.fontSize)
            }
        }

        // Дни вылета «туда»
        DayRow(
            caption = "Туда:",
            selected = state.outboundDays,
            onToggle = { day -> vm.toggleWeekendDay(day); vm.refresh() },
        )

        // Обратные билеты
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.returnEnabled,
                onCheckedChange = {
                    vm.toggleReturnEnabled(it)
                    vm.refresh()
                },
            )
            Text("Показывать обратные билеты", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.returnEnabled) {
            DayRow(
                caption = "Обратно:",
                selected = state.returnDays,
                onToggle = { day -> vm.toggleReturnDay(day); vm.refresh() },
            )
        }
    }

    val dates = state.weekendDepartureDates()
    if (dates.isNotEmpty()) {
        val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", RU) }
        Text(
            text = dates.joinToString(", ") { it.format(fmt).replaceFirstChar { c -> c.uppercase(RU) } },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** Горизонтальная строка чипов дней недели с подписью («Туда:» / «Обратно:»). */
@Composable
private fun DayRow(
    caption: String,
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            DayOfWeek.MONDAY to "Пн",
            DayOfWeek.TUESDAY to "Вт",
            DayOfWeek.WEDNESDAY to "Ср",
            DayOfWeek.THURSDAY to "Чт",
            DayOfWeek.FRIDAY to "Пт",
            DayOfWeek.SATURDAY to "Сб",
            DayOfWeek.SUNDAY to "Вс",
        ).forEach { (day, short) ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(short) },
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Загружаем цены с flypobeda.ru…")
        }
    }
}

@Composable
private fun EmptyBlock(state: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (state.errors.isNotEmpty()) "Не удалось загрузить цены.\nПроверьте интернет и повторите."
            else "Нет предложений на выбранные даты.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteList(state: UiState, visible: List<PobedaRepository.RoutePrices>) {
    val dayFmt = remember { DateTimeFormatter.ofPattern("dd.MM", RU) }
    val longFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", RU) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.errors.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Часть запросов не удалась (${state.errors.size}).",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.mode == SearchMode.WEEKENDS && state.returnEnabled) {
            // Пары «туда/обратно» в одной карточке: под ценой вылета — цена возврата.
            val pairs = state.tripPairs(state.weekendDepartureDates())
            items(pairs, key = { "p-${it.route.hubIata}-${it.route.arrivalIata}" }) { pair ->
                TripPairCard(pair, state, dayFmt, longFmt)
            }
        } else if (state.mode == SearchMode.WEEKENDS) {
            items(visible, key = { "r-${it.hubIata}-${it.arrivalIata}" }) { route ->
                RouteCard(route, dayFmt)
            }
        } else {
            items(visible, key = { "r-${it.hubIata}-${it.arrivalIata}" }) { route ->
                RouteCard(route, dayFmt)
            }
        }
    }
}

/** Карточка-пара: маршруты туда и сразу под ними цены обратных билетов по тем же датам. */
@Composable
private fun TripPairCard(
    pair: UiState.TripPair,
    state: UiState,
    dayFmt: DateTimeFormatter,
    longFmt: DateTimeFormatter,
) {
    val route = pair.route
    val hubName = PobedaRepository.HUBS.first { it.iata == route.hubIata }.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$hubName ↔ ${route.arrivalName.ifBlank { route.arrivalIata }}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                pair.cheapestTotal?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatPrice(it),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "туда + обратно",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            pair.legs.forEach { leg ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    // --- Туда ---
                    PriceLine(
                        label = "✈ Туда · " + leg.outboundDate.format(longFmt)
                            .replaceFirstChar { it.uppercase(RU) },
                        entry = leg.outbound,
                        missingText = "нет тарифа",
                    )
                    // --- Обратно (сразу под ценой вылета) ---
                    if (leg.returns.isEmpty()) {
                        Text(
                            "↵ Обратно: нет тарифов на выбранные дни",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                    } else {
                        val minRet = leg.returns.minOf { it.second.price }
                        leg.returns.forEach { (date, entry) ->
                            PriceLine(
                                label = "↵ Обратно · " + date.format(longFmt)
                                    .replaceFirstChar { it.uppercase(RU) },
                                entry = entry,
                                missingText = null,
                                highlight = entry.price == minRet,
                            )
                        }
                    }
                }
                if (leg !== pair.legs.last()) HorizontalDivider(Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/** Одна строка: «✈ Туда · Пт 26 дек» ……… «5 499 ₽». */
@Composable
private fun PriceLine(
    label: String,
    entry: PobedaRepository.PriceEntry?,
    missingText: String?,
    highlight: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry != null) {
            Text(
                formatPrice(entry.price),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                missingText ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouteCard(route: PobedaRepository.RoutePrices, dayFmt: DateTimeFormatter) {
    val hubName = PobedaRepository.HUBS.first { it.iata == route.hubIata }.name
    val cheapest = route.cheapest
    val sorted = route.prices.values.sortedBy { it.depDate }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "$hubName → ${route.arrivalName.ifBlank { route.arrivalIata }}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Код: ${route.arrivalIata}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cheapest?.let {
                    Text(
                        formatPrice(it.price),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Цены по дням — горизонтальная лента
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sorted.forEach { entry ->
                    val isMin = entry.price == cheapest?.price
                    val date = runCatching { LocalDate.parse(entry.depDate).format(dayFmt) }
                        .getOrDefault(entry.depDate)
                    ElevatedCard(
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.background(
                            if (isMin) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(date, style = MaterialTheme.typography.labelSmall)
                            Text(
                                formatPrice(entry.price),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isMin) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatPrice(price: Int): String =
    String.format(Locale("ru"), "%,d", price).replace(',', ' ') + " ₽"
