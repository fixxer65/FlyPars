package ru.pobedamonitor

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import ru.pobedamonitor.data.PobedaRepository
import ru.pobedamonitor.ui.PobedaViewModel
import ru.pobedamonitor.ui.UiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        when {
            state.isLoading && state.routes.isEmpty() -> LoadingBlock()
            state.routes.isEmpty() && !state.isLoading -> EmptyBlock(state)
            else -> RouteList(state)
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
                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
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

@Composable
private fun FilterRow(state: UiState, vm: PobedaViewModel) {
    val context = LocalContext.current
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("ru")) }

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
                imageVector = androidx.compose.material.icons.Icons.Default.DateRange,
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
private fun RouteList(state: UiState) {
    val dayFmt = remember { DateTimeFormatter.ofPattern("dd.MM", Locale("ru")) }
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
        items(state.routes, key = { "${it.hubIata}-${it.arrivalIata}" }) { route ->
            RouteCard(route, dayFmt)
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
                        containerColor = if (isMin)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
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
