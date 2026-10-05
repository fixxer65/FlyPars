package ru.pobedamonitor

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.pobedamonitor.data.Airport
import ru.pobedamonitor.data.Airports
import ru.pobedamonitor.data.PobedaRepository
import ru.pobedamonitor.ui.PobedaMonitorTheme
import ru.pobedamonitor.ui.PobedaViewModel
import ru.pobedamonitor.ui.SearchMode
import ru.pobedamonitor.ui.DirectFilter
import ru.pobedamonitor.ui.ListFilter
import ru.pobedamonitor.ui.SortOrder
import ru.pobedamonitor.ui.UiState
import ru.pobedamonitor.ui.headerBrush
import ru.pobedamonitor.ui.screenBackgroundBrush
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val RU = Locale("ru")

/** v2.23: русские названия месяцев в именительном падеже («Ноябрь 2026», а не «ноября»). */
private val MONTHS_NOMINATIVE = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)

/** Короткий месяц для строк «Пт 9 окт» (род. падеж здесь корректен в составе даты). */
private fun ruMonthShort(m: java.time.Month): String = when (m) {
    java.time.Month.JANUARY -> "янв"; java.time.Month.FEBRUARY -> "фев"
    java.time.Month.MARCH -> "мар"; java.time.Month.APRIL -> "апр"
    java.time.Month.MAY -> "мая"; java.time.Month.JUNE -> "июн"
    java.time.Month.JULY -> "июл"; java.time.Month.AUGUST -> "авг"
    java.time.Month.SEPTEMBER -> "сен"; java.time.Month.OCTOBER -> "окт"
    java.time.Month.NOVEMBER -> "ноя"; java.time.Month.DECEMBER -> "дек"
}

/** «Ноябрь 2026» — именительный падеж, единый для всех заголовков месяца. */
private fun monthTitleNominative(ym: java.time.YearMonth): String =
    "${MONTHS_NOMINATIVE[ym.monthValue - 1]} ${ym.year}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // v2.4: если открыли приложение тапом по уведомлению «цена упала» —
        // показываем сразу избранные направления.
        if (intent?.getBooleanExtra(ru.pobedamonitor.notify.PriceDropNotifier.EXTRA_OPEN_FAVORITES, false) == true) {
            pendingOpenFavorites = true
        }
        setContent {
            PobedaMonitorTheme {
                MainScreen(
                    openFavoritesRequested = pendingOpenFavorites,
                    onFavoritesOpened = { pendingOpenFavorites = false },
                )
            }
        }
    }

    private var pendingOpenFavorites = false

    /** Одноразовый запрос разрешения на уведомления (Android 13+). */
    val notifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingNotifyCallback?.invoke(granted)
        pendingNotifyCallback = null
    }

    var pendingNotifyCallback: ((Boolean) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTask: повторный тап по уведомлению приходит сюда.
        if (intent.getBooleanExtra(ru.pobedamonitor.notify.PriceDropNotifier.EXTRA_OPEN_FAVORITES, false)) {
            pendingOpenFavorites = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    openFavoritesRequested: Boolean = false,
    onFavoritesOpened: () -> Unit = {},
    vm: PobedaViewModel = viewModel(
        factory = PobedaViewModel.factory(LocalContext.current),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(scroll)

    // v2.5: экран графика истории цен по направлению (открывается тапом по карточке).
    var historyRoute by remember { mutableStateOf<PobedaRepository.RoutePrices?>(null) }
    // v2.12: экран «Мои цели» — список всех заданных целевых цен.
    var showTargets by remember { mutableStateOf(false) }
    // v2.5: экспорт истории в CSV — через системный «Поделиться».
    val context = LocalContext.current
    val shareCsv: () -> Unit = {
        val file = vm.exportHistoryCsv()
        if (file == null) {
            android.widget.Toast.makeText(
                context, "История цен пока пуста — подождите пару обновлений",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } else {
            val uri = ru.pobedamonitor.data.CsvExporter(context).uriFor(file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Pobeda: история цен")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Экспорт истории цен (CSV)"))
        }
    }

    // v2.4: тап по уведомлению «цена упала» — переключаемся на избранные.
    LaunchedEffect(openFavoritesRequested) {
        if (openFavoritesRequested) {
            vm.setListFilter(ListFilter.FAVORITES)
            onFavoritesOpened()
        }
    }

    Scaffold(
        modifier = Modifier.background(screenBackgroundBrush()),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            Surface(
                shape = RoundedCornerShape(bottomStartPercent = 30, bottomEndPercent = 30),
                color = Color.Transparent,
                shadowElevation = 6.dp,
            ) {
                Box(Modifier.background(headerBrush())) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Pobeda",
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "авиамонитор",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                            titleContentColor = Color.White,
                        ),
                        scrollBehavior = scrollBehavior,
                        // v2.12: быстрый доступ к списку всех целевых цен.
                        actions = {
                            if (state.targetPrices.isNotEmpty()) {
                                TextButton(onClick = { showTargets = true }) {
                                    Text(
                                        "🎯 ${state.targetPrices.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // v2.20: единая центрированная колонка (max 460dp) с меньшими
                // полями — контент ближе к центру, длинные подписи не обрезаются.
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            RateAndUpdatedChip(state)
            // v2.20: все секции в одной центрированной колонке шириной до 460dp —
            // на широких экранах текст не «расползается» по краям.
            Box(Modifier.fillMaxWidth().widthIn(max = 460.dp)) { FilterCard(state = state, vm = vm) }
            Spacer(Modifier.height(8.dp))
            // v2.18: уведомления — отдельная складываемая карточка под параметрами.
            Box(Modifier.fillMaxWidth().widthIn(max = 460.dp)) { NotificationsCard(state = state, vm = vm) }

            val visible = state.visibleRoutes()
            when {
                state.isLoading && visible.isEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    LoadingBlock()
                }
                visible.isEmpty() && !state.isLoading -> {
                    Spacer(Modifier.height(8.dp))
                    EmptyBlock(state, vm)
                }
                else -> {
                    Spacer(Modifier.height(8.dp))
                    ResultsHeader(state, visible, onExportCsv = shareCsv)
                    RefreshableResults(vm, state, visible, scrollBehavior) { route ->
                        historyRoute = route
                    }
                }
            }
        }
    }

    // v2.5: модалка с графиком истории цен направления (открывается тапом по карточке).
    historyRoute?.let { route ->
        PriceHistoryDialog(
            route = route,
            vm = vm,
            state = state,
            onDismiss = { historyRoute = null },
        )
    }

    // v2.12: экран «Мои цели» — все заданные целевые цены списком.
    if (showTargets) {
        TargetsScreen(
            state = state,
            vm = vm,
            onDismiss = { showTargets = false },
        )
    }
}

/** Компактная плашка: курс BYN + время обновления в одну строку по центру. */
@Composable
private fun RateAndUpdatedChip(state: UiState) {
    // v2.19: источник курса больше не подписываем — длинная строка обрезалась по краям;
    // смысл тот же, строкa всегда помещается целиком.
    val rateText = when {
        state.bynPerRub != null ->
            "Курс: 1 ₽ = ${String.format(RU, "%.3f", state.bynPerRub)} BYN"
        state.rateError != null -> "Курс BYN недоступен"
        else -> "Загружаем курс BYN…"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 1.dp,
        ) {
            Text(
                buildString {
                    append(rateText)
                    state.lastUpdated?.let { append("   ·   обновлено $it") }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Карточка «Параметры поиска».
 * v2.18: редизайн логики — карточка больше не сворачивается целиком; она всегда
 * содержит три секции с индивидуальными заголовками-переключателями:
 *   1) «Где искать» — откуда + куда (одна строка);
 *   2) «Когда» — режим 📅/🌤 + период (дата+дни или месяц+дни недели+обратно);
 *   3) «Список» — ⭐ избранные, ✈ тип рейса, сортировка (одна горизонтальная строка).
 * Секция «🔔 Уведомления» вынесена из карточки в отдельную складываемую карточку
 * внизу (по умолчанию свёрнута в одну строку-заголовок). Весь функционал сохранён,
 * но объём прокрутки и количество тапов до нужной настройки заметно меньше.
 */
@Composable
private fun FilterCard(state: UiState, vm: PobedaViewModel) {
    // v2.22: вся карточка сворачивается в одну аккуратную строку-шапку
    // (как было до v2.18 — тап по шапке или стрелке разворачивает обратно).
    var open by rememberSaveable("filterCardOpen") { mutableStateOf(true) }
    // v2.9: ограничиваем высоту карточки ~65% экрана. Внутри — verticalScroll.
    val maxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.65f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // ── Шапка: всегда видима, тап сворачивает/разворачивает ──
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Tune, null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Параметры поиска",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                filterWhereSummary(state) + " · " +
                    when (state.mode) {
                        SearchMode.ALL_DAYS -> "${state.daysCount} дн."
                        SearchMode.WEEKENDS -> state.month.atDay(1)
                            .format(DateTimeFormatter.ofPattern("LLL", RU))
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (open) "Свернуть" else "Развернуть",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (open) 180f else 0f)
                    .clickable { open = !open },
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = open) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    // v2.20: единый внутренний отступ 10dp + центрирование колонок —
                    // элементы выровнены по одной сетке и не расползаются к краям.
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── «Где искать»: хабы + куда (одна секция) ────────────────
                SectionHeader("Где искать", filterWhereSummary(state))
                HubChips(state, vm)
                Spacer(Modifier.height(6.dp))
                DestinationPicker(state, vm)

                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // ── «Когда»: режим + период ────────────────────────────────
                SectionHeader("Когда", if (state.mode == SearchMode.ALL_DAYS)
                    "с ${state.fromDate.dayOfMonth}.${"%02d".format(state.fromDate.monthValue)} · ${state.daysCount} дн."
                else monthTitleNominative(state.month))
                ModeToggle(state, vm)
                Spacer(Modifier.height(5.dp))
                if (state.mode == SearchMode.ALL_DAYS) {
                    AllDaysControls(state, vm)
                } else {
                    WeekendsControls(state, vm) { vm.refresh() }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // ── «Список» (избранное / тип рейса / сортировка) ──────────
                SectionHeader("Список", "фильтры и порядок")
                ListTogglesRow(state, vm)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** Компактный заголовок секции карточки: название + короткая сводка. */
@Composable
private fun SectionHeader(title: String, summary: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun filterWhereSummary(state: UiState): String {
    val hubs = state.hubsSelected.sorted().joinToString("/") {
        PobedaRepository.HUBS.firstOrNull { h -> h.iata == it }?.name ?: it
    }.ifBlank { "—" }
    val dest = when {
        state.destinationsSelected.isEmpty() -> "все"
        state.destinationsSelected.size == 1 ->
            Airports.nameOf(state.destinationsSelected.first())
        else -> "${state.destinationsSelected.size} напр."
    }
    return "$hubs → $dest"
}

/** Сегментированный переключатель режима поиска: 📅 Все дни / 🌤 Выходные. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(state: UiState, vm: PobedaViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        data class Opt(val mode: SearchMode, val label: String)
        listOf(
            Opt(SearchMode.ALL_DAYS, "📅 Все дни"),
            Opt(SearchMode.WEEKENDS, "🌤 Выходные"),
        ).forEach { opt ->
            val selected = state.mode == opt.mode
            Surface(
                onClick = { vm.setMode(opt.mode); vm.refreshSoon() },
                shape = RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    // v2.19: компакчнее — меньше вертикальные отступы.
                    Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Одна горизонтальная строка: ⭐ избранное, тип рейса, сортировка. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListTogglesRow(state: UiState, vm: PobedaViewModel) {
    // v2.19: чипы уменьшены до CompactChip/SmallChip — строка целиком помещается
    // на узких экранах без обрезки подписей по краям.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        SmallChip(
            selected = state.listFilter == ListFilter.FAVORITES,
            label = "⭐ ${state.favorites.size}",
            icon = if (state.listFilter == ListFilter.FAVORITES) Icons.Default.Star
                   else Icons.Outlined.StarBorder,
            onClick = {
                vm.setListFilter(
                    if (state.listFilter == ListFilter.FAVORITES) ListFilter.ALL
                    else ListFilter.FAVORITES
                )
            },
        )
        // Тип рейса: все -> только прямые -> только стыковочные -> все
        val directLabel = when (state.directFilter) {
            DirectFilter.ALL -> "Рейсы: все"
            DirectFilter.DIRECT_ONLY -> "✈ Прямые"
            DirectFilter.TRANSFER_ONLY -> "🔄 Стыковки"
        }
        SmallChip(
            selected = state.directFilter != DirectFilter.ALL,
            label = directLabel,
            icon = null,
            onClick = {
                val next = when (state.directFilter) {
                    DirectFilter.ALL -> DirectFilter.DIRECT_ONLY
                    DirectFilter.DIRECT_ONLY -> DirectFilter.TRANSFER_ONLY
                    DirectFilter.TRANSFER_ONLY -> DirectFilter.ALL
                }
                vm.setDirectFilter(next)
            },
        )
        SortDropdown(state, vm)
    }
}

/** Компактный чип-переключатель с опциональной иконкой (v2.19). */
@Composable
private fun SmallChip(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon?.let { Icon(it, null, modifier = Modifier.size(15.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** v2.15: горизонтальная лента самых важных переключателей — режим поиска, фильтр
 *  «избранные», тип рейса (прямые/стыковочные), быстрый выбор даты, компактная
 *  сортировка и кнопка «⚙ Ещё». Заменяет три крупных сегментированных блока.
 *  v2.18: оставлена для совместимости, больше не используется в FilterCard. */
@Suppress("unused")
@Composable
private fun CompactChipRow(
    state: UiState,
    vm: PobedaViewModel,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = {
                val next = if (state.mode == SearchMode.ALL_DAYS)
                    SearchMode.WEEKENDS else SearchMode.ALL_DAYS
                vm.setMode(next)
            },
            label = {
                Text(
                    if (state.mode == SearchMode.ALL_DAYS) "📅 Все дни" else "🌤 Выходные",
                    maxLines = 1,
                    fontWeight = FontWeight.Medium,
                )
            },
            shape = RoundedCornerShape(50),
        )
        FilterChip(
            selected = state.listFilter == ListFilter.FAVORITES,
            onClick = {
                vm.setListFilter(
                    if (state.listFilter == ListFilter.FAVORITES) ListFilter.ALL
                    else ListFilter.FAVORITES
                )
            },
            label = { Text("⭐ ${state.favorites.size}", maxLines = 1) },
            leadingIcon = {
                Icon(
                    if (state.listFilter == ListFilter.FAVORITES) Icons.Default.Star
                    else Icons.Outlined.StarBorder,
                    null,
                    modifier = Modifier.size(18.dp),
                )
            },
            shape = RoundedCornerShape(50),
        )
        val directLabel = when (state.directFilter) {
            DirectFilter.ALL -> "Рейсы: все"
            DirectFilter.DIRECT_ONLY -> "✈ Только прямые"
            DirectFilter.TRANSFER_ONLY -> "🔄 Стыковки"
        }
        FilterChip(
            selected = state.directFilter != DirectFilter.ALL,
            onClick = {
                val next = when (state.directFilter) {
                    DirectFilter.ALL -> DirectFilter.DIRECT_ONLY
                    DirectFilter.DIRECT_ONLY -> DirectFilter.TRANSFER_ONLY
                    DirectFilter.TRANSFER_ONLY -> DirectFilter.ALL
                }
                vm.setDirectFilter(next)
            },
            label = { Text(directLabel, maxLines = 1) },
            trailingIcon = {
                if (state.directFilter != DirectFilter.ALL) {
                    Icon(Icons.Default.Close, "Сбросить", modifier = Modifier.size(16.dp))
                }
            },
            shape = RoundedCornerShape(50),
        )
        if (state.mode == SearchMode.ALL_DAYS) {
            val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM", RU) }
            val uiContext = LocalContext.current
            AssistChip(
                onClick = {
                    val context = uiContext
                    val d = state.fromDate
                    DatePickerDialog(
                        context,
                        { _, y, m, day ->
                            vm.setDate(LocalDate.of(y, m + 1, day))
                        },
                        d.year, d.monthValue - 1, d.dayOfMonth,
                    ).apply {
                        datePicker.minDate = System.currentTimeMillis() - 24L * 3600 * 1000
                    }.show()
                },
                label = { Text("с ${state.fromDate.format(dateFmt)} · ${state.daysCount} дн.", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.DateRange, null, modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(50),
            )
        } else {
            AssistChip(
                onClick = { setExpanded(true) },
                label = {
                    Text(
                        // v2.23: именительный падеж («Ноябрь 2026», а не «ноября»).
                        monthTitleNominative(state.month),
                        maxLines = 1,
                    )
                },
                shape = RoundedCornerShape(50),
            )
        }
        SortDropdown(state, vm)
        AssistChip(
            onClick = { setExpanded(!expanded) },
            label = { Text(if (expanded) "⚙ Свернуть ▴" else "⚙ Ещё ▾", maxLines = 1) },
            shape = RoundedCornerShape(50),
        )
    }
}

/** v2.15: компактное выпадающее меню сортировки вместо трёх кнопок. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(state: UiState, vm: PobedaViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        // v2.19: тот же SmallChip-стиль, что и соседние чипы — единая высота строки.
        SmallChip(
            selected = state.sortOrder != SortOrder.NONE,
            label = when (state.sortOrder) {
                SortOrder.NONE -> "Сортировка ▾"
                SortOrder.ASC -> "Цена ↑"
                SortOrder.DESC -> "Цена ↓"
            },
            icon = Icons.Default.Sort,
            onClick = { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(
                SortOrder.NONE to "Без сортировки",
                SortOrder.ASC to "Цена ↑ сначала дешёвые",
                SortOrder.DESC to "Цена ↓ сначала дорогие",
            ).forEach { (order, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (state.sortOrder == order) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = {
                        if (state.sortOrder == order) Icon(Icons.Default.Check, null)
                    },
                    onClick = { vm.setSortOrder(order); menuOpen = false },
                )
            }
        }
    }
}


/**
 * Включает уведомления и запрашивает разрешение POST_NOTIFICATIONS (Android 13+).
 * На более старых версиях разрешение не нужно — включаем сразу.
 */
private fun enableNotificationsWithPermission(activity: MainActivity?, vm: PobedaViewModel) {
    if (Build.VERSION.SDK_INT < 33 || activity == null) {
        vm.setNotificationsEnabled(true)
        return
    }
    val granted = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) {
        vm.setNotificationsEnabled(true)
    } else {
        activity.pendingNotifyCallback = { accepted -> vm.setNotificationsEnabled(accepted) }
        activity.notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Строка управления уведомлениями «цена упала» внутри карточки фильтров (v2.4). */
@Composable
private fun NotificationsRow(state: UiState, vm: PobedaViewModel) {
    val activity = LocalContext.current as? MainActivity
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🔔",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Уведомлять о падении цены",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Только для ⭐ избранных · не чаще раза в 3 часа",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // v2.8: WorkManager проверяет цены и шлёт уведомления даже с закрытым приложением.
                // v2.11: интервал сокращён до ~1 часа; v2.12: интервал выбирается пользователем.
                Text(
                    "Работает в фоне без открытия приложения",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = { on ->
                    if (on) enableNotificationsWithPermission(activity, vm)
                    else vm.setNotificationsEnabled(false)
                },
            )
        }
        if (state.notificationsEnabled) {
            Spacer(Modifier.height(6.dp))

            // v2.19: подписи вынесены над группами чипов, сами группы центрированы —
            // строки больше не упираются в края экрана и не обрезаются.

            // v2.6: база цены — «только туда» или «туда и обратно».
            Text(
                "Считать цену:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                SmallChip(
                    selected = !state.notifyRoundTrip,
                    label = "только туда",
                    icon = null,
                    onClick = { vm.setNotifyRoundTrip(false) },
                )
                SmallChip(
                    selected = state.notifyRoundTrip,
                    label = "туда + обратно",
                    icon = null,
                    onClick = { vm.setNotifyRoundTrip(true) },
                )
            }
            if (state.notifyRoundTrip) {
                Text(
                    "Обратные цены подгружаются в режиме выходных; без них следим за ценой «только туда».",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            // v2.6: режим порога — проценты или конкретная сумма (₽).
            Text(
                "Реагировать на падение:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                SmallChip(
                    selected = state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT,
                    label = "в %",
                    icon = null,
                    onClick = { vm.setNotifyMode(ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT) },
                )
                SmallChip(
                    selected = state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_AMOUNT,
                    label = "на сумму ₽",
                    icon = null,
                    onClick = { vm.setNotifyMode(ru.pobedamonitor.data.FavoritesRepository.MODE_AMOUNT) },
                )
            }

            Spacer(Modifier.height(6.dp))

            if (state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "−${state.dropThresholdPercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(52.dp),
                    )
                    Slider(
                        value = state.dropThresholdPercent.toFloat(),
                        onValueChange = { vm.setDropThreshold(it.toInt()) },
                        valueRange = 5f..30f,
                        steps = 4,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                var amountText by remember(state.dropThresholdAmount) {
                    mutableStateOf(if (state.dropThresholdAmount > 0) state.dropThresholdAmount.toString() else "")
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val filtered = raw.filter { it.isDigit() }.take(7)
                        amountText = filtered
                        vm.setDropAmountRub(filtered.toIntOrNull() ?: 0)
                    },
                    label = { Text("Падение минимум на, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    listOf(1000, 2000, 3000, 5000).forEach { preset ->
                        val amtLabel = String.format(Locale("ru"), "%,d", preset).replace(',', ' ') + " ₽"
                        SmallChip(
                            selected = state.dropThresholdAmount == preset,
                            label = amtLabel,
                            icon = null,
                            onClick = { vm.setDropAmountRub(preset) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // v2.12: выбор интервала фоновой проверки цен (минимум Android — 1 час).
            Text(
                "Проверять в фоне:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                listOf(
                    1 to "раз в час",
                    6 to "раз в 6 ч",
                    24 to "раз в сутки",
                ).forEach { (hours, label) ->
                    SmallChip(
                        selected = state.checkIntervalHours == hours,
                        label = label,
                        icon = null,
                        onClick = { vm.setCheckInterval(hours) },
                    )
                }
            }
            if (state.checkIntervalHours > 1) {
                Text(
                    "Android выполняет периодические проверки с плавающим окном ±15 мин; чаще раза в час система не позволяет.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Короткое описание текущих фильтров. v2.18: используется заголовком карточки
 *  «🔔 Уведомления» (показывает, что именно отслеживается, в свёрнутом виде). */
private fun filterSummary(state: UiState): String {
    val hubs = state.hubsSelected.sorted().joinToString("/") {
        PobedaRepository.HUBS.firstOrNull { h -> h.iata == it }?.name ?: it
    }.ifBlank { "—" }
    val dest = when {
        state.destinationsSelected.isEmpty() -> "все направления"
        state.destinationsSelected.size == 1 ->
            Airports.nameOf(state.destinationsSelected.first())
        else -> "${state.destinationsSelected.size} напр."
    }
    return buildString {
        append(state.modeLabel())
        if (state.listFilter == ListFilter.FAVORITES) {
            append(" · ⭐ избранное (${state.favorites.size})")
        }
        append(" · ")
        append(hubs)
        append(" → ")
        append(dest)
        if (state.mode == SearchMode.WEEKENDS) {
            append(" · ")
            append(monthTitleNominative(state.month))
        }
    }
}

private fun UiState.modeLabel(): String = when (mode) {
    SearchMode.ALL_DAYS -> "Все дни"
    SearchMode.WEEKENDS -> "Выходные"
}

/**
 * v2.18: отдельная складываемая карточка «🔔 Уведомления».
 * В свёрнутом состоянии — одна строка с понятной сводкой настроек; весь блок
 * настроек (порог, база, интервал) раскрывается по тапу.
 */
@Composable
private fun NotificationsCard(state: UiState, vm: PobedaViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // v2.18: activity читаем в composable-контексте и передаём в не-composable функцию.
    val activity = LocalContext.current as? MainActivity

    val summary = if (!state.notificationsEnabled) {
        "выключены · цена упала / 🎯 цель"
    } else {
        val mode = if (state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT)
            "−${state.dropThresholdPercent}%"
        else {
            val amt = String.format(Locale("ru"), "%,d", state.dropThresholdAmount)
                .replace(',', ' ')
            "−$amt ₽"
        }
        val base = if (state.notifyRoundTrip) "туда+обратно" else "только туда"
        val interval = when (state.checkIntervalHours) {
            1 -> "раз в час"
            6 -> "раз в 6 ч"
            else -> "раз в сутки"
        }
        "вкл · $mode ($base) · проверка $interval"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            // Заголовок-переключатель + быстрый Switch прямо в нём.
            // v2.19: Switch уменьшен (scale 0.85), стрелка — вплотную без наложения;
            // сводка обрезается многоточием, а не «расползается» за край.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔔", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Уведомления",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.notificationsEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .graphicsLayer { scaleX = 0.82f; scaleY = 0.82f }
                        .width(52.dp).height(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(
                        checked = state.notificationsEnabled,
                        onCheckedChange = { on ->
                            if (on) enableNotificationsWithPermission(activity, vm)
                            else vm.setNotificationsEnabled(false)
                        },
                    )
                }
                Icon(
                    Icons.Default.ExpandMore,
                    if (expanded) "Свернуть" else "Развернуть",
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 4.dp)) {
                    NotificationsRow(state, vm)
                }
            }
        }
    }
}

/** Звёздочка избранного на карточке направления. */
@Composable
private fun FavoriteStar(code: String, state: UiState, vm: PobedaViewModel) {
    val isFav = code in state.favorites
    val tint by animateColorAsState(
        if (isFav) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.outline,
        label = "favTint",
    )
    Surface(
        onClick = { vm.toggleFavorite(code) },
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = if (isFav) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFav) "Убрать из избранного" else "В избранное",
            tint = tint,
            modifier = Modifier
                .size(34.dp)
                .padding(6.dp),
        )
    }
}

/** Сегментированный переключатель сортировки результатов по цене. */
@Composable
private fun SortTabs(state: UiState, vm: PobedaViewModel) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            data class Opt(val order: SortOrder, val label: String)
            listOf(
                Opt(SortOrder.NONE, "Без сортировки"),
                Opt(SortOrder.ASC, "Цена ↑"),
                Opt(SortOrder.DESC, "Цена ↓"),
            ).forEach { opt ->
                val selected = state.sortOrder == opt.order
                Surface(
                    onClick = { vm.setSortOrder(opt.order) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Чипы выбора хабов (Москва / Минск). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HubChips(state: UiState, vm: PobedaViewModel) {
    // v2.23: подпись «Откуда:» — отдельной строкой сверху над чипами (в v2.22 она
    // стояла в одном ряду с чипами и визуально «съезжала» вверх относительно них).
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Откуда:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
        PobedaRepository.HUBS.forEach { hub ->
            val selected = hub.iata in state.hubsSelected
            SmallChip(
                selected = selected,
                label = "${hub.name} ${hub.iata}",
                icon = null,
                onClick = { vm.toggleHub(hub.iata); vm.refreshSoon() },
            )
        }
        }
    }
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
        // v2.19: компактная строка «Куда» — единый внутренний отступ 8.dp, чтобы
        // левый край совпадал с остальными элементами карточки (без «расползания»).
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search, null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text("Куда:", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        state.destinationsSelected.isEmpty() -> "все направления"
                        state.destinationsSelected.size == 1 -> {
                            val code = state.destinationsSelected.first()
                            "${Airports.nameOf(code)} ($code)"
                        }
                        else -> "выбрано ${state.destinationsSelected.size}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.destinationsSelected.isNotEmpty()) {
                    TextButton(
                        onClick = { vm.clearDestinations() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("✕", style = MaterialTheme.typography.labelLarge) }
                } else {
                    Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(20.dp))
                }
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск города или кода…") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
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

/** Ловим клики по «только для чтения» полю-фильтру без ripple-подсветки поля. */
@Composable
private fun Modifier.clickableBox(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

/** Обычный режим: дата начала + глубина периода. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AllDaysControls(state: UiState, vm: PobedaViewModel) {
    val context = LocalContext.current
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM", RU) }

    // v2.19: строка центрирована и компактна — кнопка даты без «хвоста» года,
    // чипы дней уменьшены, всё помещается на одном экране без горизонтального
    // скролла и обрезки по краям.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Surface(
            onClick = {
                val d = state.fromDate
                DatePickerDialog(
                    context,
                    { _, y, m, day ->
                        vm.setDate(LocalDate.of(y, m + 1, day)); vm.refreshSoon()
                    },
                    d.year, d.monthValue - 1, d.dayOfMonth,
                ).apply {
                    datePicker.minDate = System.currentTimeMillis() - 24L * 3600 * 1000
                }.show()
            },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.DateRange, null, Modifier.size(16.dp))
                Text(state.fromDate.format(dateFmt),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1)
            }
        }

        listOf(7, 14, 30).forEach { days ->
            SmallChip(
                selected = state.daysCount == days,
                label = "${days} дн.",
                icon = null,
                onClick = { vm.setDaysCount(days); vm.refreshSoon() },
            )
        }
        // v2.18: кнопка точечного применения — если отложенный запрос по какой-то
        // причине не устроил, цены можно обновить сразу одним тапом.
        Surface(
            onClick = { vm.refresh() },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(15.dp))
                Text(if (state.isLoading) "Ищем…" else "Цены",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** Режим «выходных»: месяц + дни вылета «туда» + опция обратных билетов. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekendsControls(state: UiState, vm: PobedaViewModel, onApply: () -> Unit) {
    // v2.23: выбор месяца — вертикальный прокручиваемый список по тапу на название;
    // иконка-календарь справа убрана (была бессмысленной), вместо неё ▾ у текста.
    var monthPickerOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // Навигатор месяца: ◀ «Ноябрь 2026 ▾» ▶ — имена в именительном падеже.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { vm.shiftMonth(-1); vm.refreshSoon() },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(Icons.Default.ChevronRight, "Предыдущий месяц",
                        modifier = Modifier.rotate(180f).size(20.dp))
                }
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { monthPickerOpen = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        monthTitleNominative(state.month),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ExpandMore, "Выбрать месяц из списка",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { vm.shiftMonth(+1); vm.refreshSoon() },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(Icons.Default.ChevronRight, "Следующий месяц",
                        modifier = Modifier.size(20.dp))
                }
            }
        }

        // Всплывающий список месяцев (прокрутка по вертикали, как выбор направления)
        if (monthPickerOpen) {
            val months = remember(state.month) {
                (0L until 24L).map { state.month.plusMonths(it - 6L) }
            }
            DropdownMenu(
                expanded = true,
                onDismissRequest = { monthPickerOpen = false },
            ) {
                months.forEach { ym ->
                    val cur = ym == state.month
                    DropdownMenuItem(
                        text = {
                            Text(
                                monthTitleNominative(ym),
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            if (cur) Icon(Icons.Default.Check, null)
                        },
                        onClick = {
                            vm.setMonth(ym); onApply(); monthPickerOpen = false
                        },
                    )
                }
            }
        }

        // v2.18: одна компактная строка «Туда / ↕ / Обратно» вместо двух DayRow
        // и отдельного переключателя обратных билетов на всю ширину.
        CompactRoundTripRow(state, vm)

        // v2.19: сводка дат по центру (maxLines=1 + ellipsis — без «лестницы» в
        // две строки), кнопка «Показать цены» под ней по центру.
        val dates = state.weekendDepartureDates()
        if (dates.isNotEmpty()) {
            val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", RU) }
            Text(
                text = dates.joinToString(", ") {
                    it.format(fmt).replaceFirstChar { c -> c.uppercase(RU) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                onClick = onApply,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Default.Search, null, Modifier.size(15.dp))
                    Text(if (state.isLoading) "Ищем…" else "Показать цены",
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * v2.25: дни недели выходных — ВЕРТИКАЛЬНО: «Туда» сверху, широкая скруглённая
 * кнопка «Туда + обратно» посередине, «Обратно» снизу (поле появляется только
 * когда режим включён, но НЕ открывается само — тап по полю открывает список
 * столько раз, сколько нужно; повторное нажатие кнопки только включает/выключает
 * режим). Поля — выпадающие списки во всю ширину колонки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactRoundTripRow(state: UiState, vm: PobedaViewModel) {
    val shortDays = listOf(
        DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
        DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
        DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
        DayOfWeek.SUNDAY to "Вс",
    )
    var outMenu by remember { mutableStateOf(false) }
    var retMenu by remember { mutableStateOf(false) }
    val retOn = state.returnEnabled

    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── «Туда» — сверху, на всю ширину ──
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = "✈ " + when {
                    state.outboundDays.isEmpty() -> "не выбрано"
                    state.outboundDays.size == 7 -> "все дни"
                    else -> shortDays.filter { it.first in state.outboundDays }
                        .joinToString(", ") { it.second }
                },
                onValueChange = {},
                readOnly = true,
                enabled = false, // v2.26: клик перехватывает Box ниже — тап в ЛЮБОЙ
                singleLine = true, // пиксель поля открывает меню (текст остаётся читаемым)
                label = { Text("Дни вылета", maxLines = 1) },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // Прозрачный слой поверх всего поля: любой тап внутри прямоугольника
            // «Дни вылета» открывает выпадающий список.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { outMenu = true }
            )
            DropdownMenu(expanded = outMenu, onDismissRequest = { outMenu = false }) {
                shortDays.forEach { (day, short) ->
                    DropdownMenuItem(
                        text = { Text(short) },
                        leadingIcon = { Checkbox(checked = day in state.outboundDays, onCheckedChange = null) },
                        onClick = { vm.toggleWeekendDay(day); vm.refreshSoon() },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (state.outboundDays.isNotEmpty()) "Сбросить" else "Выбрать все") },
                    leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                    onClick = {
                        if (state.outboundDays.isNotEmpty()) {
                            shortDays.forEach { (d, _) -> if (d in state.outboundDays) vm.toggleWeekendDay(d) }
                        } else {
                            shortDays.forEach { (d, _) -> if (d !in state.outboundDays) vm.toggleWeekendDay(d) }
                        }
                        vm.refreshSoon()
                    },
                )
            }
        }

        // ── Широкая скруглённая кнопка-переключатель посередине ──
        Surface(
            onClick = {
                // v2.25: кнопка ТОЛЬКО включает/выключает режим. Меню дней
                // возврата больше не открывается само — пользователь тапает
                // по полю «Дни возврата» столько раз, сколько нужно.
                vm.toggleReturnEnabled(!retOn); vm.refreshSoon()
            },
            shape = RoundedCornerShape(26.dp),
            color = if (retOn) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (retOn) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(18.dp))
                Text(
                    if (retOn) "Туда + обратно: вкл" else "Туда + обратно: выкл",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }

        // ── «Обратно»: v2.22 — поле показывается ТОЛЬКО когда кнопка нажата ──
        AnimatedVisibility(visible = retOn) {
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = "↵ " + when {
                        state.returnDays.isEmpty() -> "выберите дни"
                        state.returnDays.size == 7 -> "все дни"
                        else -> shortDays.filter { it.first in state.returnDays }
                            .joinToString(", ") { it.second }
                    },
                    onValueChange = {},
                    readOnly = true,
                    enabled = false, // v2.26: тап в ЛЮБУЮ точку поля открывает меню
                    singleLine = true,
                    label = { Text("Дни возврата", maxLines = 1) },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, null,
                            tint = MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Прозрачный слой поверх всего поля: любой тап внутри прямоугольника
                // «Дни возврата» открывает выпадающий список (менять дни можно много раз).
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { retMenu = true }
                )
                DropdownMenu(expanded = retMenu, onDismissRequest = { retMenu = false }) {
                    shortDays.forEach { (day, short) ->
                        DropdownMenuItem(
                            text = { Text(short) },
                            leadingIcon = { Checkbox(checked = day in state.returnDays, onCheckedChange = null) },
                            onClick = { vm.toggleReturnDay(day); vm.refreshSoon() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (state.returnDays.isNotEmpty()) "Сбросить" else "Выбрать все") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                        onClick = {
                            if (state.returnDays.isNotEmpty()) {
                                shortDays.forEach { (d, _) -> if (d in state.returnDays) vm.toggleReturnDay(d) }
                            } else {
                                shortDays.forEach { (d, _) -> if (d !in state.returnDays) vm.toggleReturnDay(d) }
                            }
                            vm.refreshSoon()
                        },
                    )
                }
            }
        }
    }
}

/** Горизонтальная строка чипов дней недели с подписью («Туда:» / «Обратно:»). */
@Composable
private fun DayRow(
    caption: String,
    selected: Set<DayOfWeek>,
    accent: Color,
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
            color = accent,
            fontWeight = FontWeight.Bold,
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
            val isSelected = day in selected
            val chipColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.16f)
                              else MaterialTheme.colorScheme.surface,
                label = "daychip-$short",
            )
            Surface(
                onClick = { onToggle(day) },
                shape = CircleShape,
                color = chipColor,
                contentColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (isSelected) null else
                    androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    short,
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Шапка результатов: количество направлений + лучшая цена периода. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshableResults(
    vm: PobedaViewModel,
    state: UiState,
    visible: List<PobedaRepository.RoutePrices>,
    scrollBehavior: TopAppBarScrollBehavior?,
    onOpenHistory: (PobedaRepository.RoutePrices) -> Unit = {},
) {
    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = { vm.refreshRate(); vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        RouteList(state, visible, vm, scrollBehavior, onOpenHistory)
    }
}

@Composable
private fun ResultsHeader(
    state: UiState,
    visible: List<PobedaRepository.RoutePrices>,
    onExportCsv: () -> Unit = {},
) {
    val bestTotal = state.bestPairTotal(state.weekendDepartureDates())
    val subtitle = when {
        state.mode == SearchMode.WEEKENDS && state.returnEnabled && bestTotal != null ->
            "лучшие выходные: всего ${formatPrice(bestTotal)}"
        state.mode == SearchMode.WEEKENDS -> "направления за выбранные дни месяца"
        else -> "прямые рейсы на период ${state.fromDate.format(DateTimeFormatter.ofPattern("d MMM", RU))} +"
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Найдено: ${visible.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Потяните вниз, чтобы обновить ↓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            )
        }
        if (state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v2.5: экспорт всей накопленной истории цен в CSV
                TextButton(onClick = onExportCsv) {
                    Text(
                        "CSV",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            }
        } else {
            TextButton(onClick = onExportCsv) {
                Text(
                    "📄 CSV",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Flight,
                null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Загружаем цены с flypobeda.ru…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyBlock(state: UiState, vm: PobedaViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    state.errors.isNotEmpty() ->
                        "Не удалось загрузить цены.\nПроверьте интернет и\nпотяните список вниз для обновления."
                    state.listFilter == ListFilter.FAVORITES ->
                        "В избранном пока пусто.\nОтметьте направления ⭐\nи они появятся здесь и в виджете."
                    else -> "Нет предложений на выбранные даты."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.listFilter == ListFilter.FAVORITES && state.errors.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.setListFilter(ListFilter.ALL) }) {
                    Text("Показать все направления")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteList(
    state: UiState,
    visible: List<PobedaRepository.RoutePrices>,
    vm: PobedaViewModel,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onOpenHistory: (PobedaRepository.RoutePrices) -> Unit = {},
) {
    val dayFmt = remember { DateTimeFormatter.ofPattern("dd.MM", RU) }
    val longFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", RU) }

    LazyColumn(
        modifier = if (scrollBehavior != null)
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        else Modifier,
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // v2.9: «буфер» в начале списка цен — невидимая полоска, которая никогда не
        // перехватывает жесты (awaitEachGesture без awaitFirstDown завершается сразу).
        // Благодаря ей развёрнутая карточка параметров с её внутренним скроллом не мешает
        // прокрутке списка: вертикальный жест всегда доходит до LazyColumn — даже в режиме
        // «туда и обратно», где панель параметров выше всего.
        item(key = "scroll-buffer") {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture { }
                    },
            )
        }

        if (state.errors.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "⚠️ Часть запросов не удалась (${state.errors.size}). Обновите ещё раз.",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (state.mode == SearchMode.WEEKENDS && state.returnEnabled) {
            val pairs = state.tripPairs(state.weekendDepartureDates())
            items(pairs, key = { "p-${it.route.hubIata}-${it.route.arrivalIata}" }) { pair ->
                TripPairCard(pair, state, vm, longFmt, onOpenHistory)
            }
        } else {
            items(visible, key = { "r-${it.hubIata}-${it.arrivalIata}" }) { route ->
                RouteCard(route, state, vm, dayFmt, onOpenHistory)
            }
        }
    }
}

/** Карточка-пара: свёрнута по умолчанию (только лучшая цена месяца), по тапу
 *  разворачивается и показывает ВСЕ пары «туда+обратно» — каждая со своей суммой. */
@Composable
private fun TripPairCard(
    pair: UiState.TripPair,
    state: UiState,
    vm: PobedaViewModel,
    longFmt: DateTimeFormatter,
    onOpenHistory: (PobedaRepository.RoutePrices) -> Unit = {},
) {
    val route = pair.route
    val hubName = PobedaRepository.HUBS.first { it.iata == route.hubIata }.name
    var expanded by rememberSaveable("${route.hubIata}-${route.arrivalIata}") {
        mutableStateOf(false)
    }
    val combos = pair.combos

    ElevatedCard(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (expanded) 5.dp else 2.dp,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Иконка-«сквознячок» маршрута
                Surface(
                    shape = CircleShape,
                    color = if (expanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Flight,
                            null,
                            Modifier.size(23.dp).rotate(if (expanded) 45f else 0f),
                            tint = if (expanded) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "$hubName ↔ ${route.arrivalName.ifBlank { route.arrivalIata }}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    state.weather[route.arrivalIata]?.let { w ->
                        Text(
                            "${w.icon} ${formatTemp(w.tempC)} · ${w.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                        )
                    } ?: Text(
                        route.arrivalIata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FavoriteStar("${route.hubIata}-${route.arrivalIata}", state, vm)
                pair.cheapestTotal?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    formatPrice(it),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                formatByn(it, state.bynPerRub)?.let { byn ->
                                    Text(
                                        "≈ $byn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                            .copy(alpha = 0.8f),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        // v2.5: график истории цен направления
                        Text(
                            "📉 история ▸",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onOpenHistory(route) },
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (expanded) "свернуть ▴" else "все выходные ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))

            if (combos.isEmpty()) {
                Text(
                    "Нет доступных пар туда+обратно на выбранные дни.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                val bestTotal = combos.minOf { it.total }
                combos.forEachIndexed { index, combo ->
                    val isBest = combo.total == bestTotal
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isBest -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            else -> Color.Transparent
                        },
                        border = if (isBest) androidx.compose.foundation.BorderStroke(
                            1.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                            else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Row(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                // v2.13: две строки — туда и обратно, каждая с временем вылета
                                // и пометкой «прямой/стык.»
                                Text(
                                    "✈ " + combo.legLabel(
                                        combo.depDate, combo.outboundDepTime,
                                        combo.outboundDirect, longFmt,
                                        combo.outboundDepVerified,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isBest) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "↵ " + (if (combo.approxReturn) "≈" else "") +
                                        combo.legLabel(
                                            combo.retDate, combo.returnDepTime,
                                            combo.returnDirect, longFmt,
                                            combo.returnDepVerified,
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isBest) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${formatPrice(combo.outboundPrice)} + ${formatPrice(combo.returnPrice)}" +
                                        if (isBest) "   · 🔥 лучший вариант" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isBest) MaterialTheme.colorScheme.onSecondaryContainer
                                            .copy(alpha = 0.75f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatPrice(combo.total),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isBest) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isBest) MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                                formatByn(combo.total, state.bynPerRub)?.let { byn ->
                                    Text(
                                        "≈ $byn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: PobedaRepository.RoutePrices,
    state: UiState,
    vm: PobedaViewModel,
    dayFmt: DateTimeFormatter,
    onOpenHistory: (PobedaRepository.RoutePrices) -> Unit = {},
) {
    val hubName = PobedaRepository.HUBS.first { it.iata == route.hubIata }.name
    val cheapest = route.cheapest
    val sorted = route.prices.values.sortedBy { it.depDate }
    // v2.7: целевая цена направления (уведомление при достижении)
    val codeKey = "${route.hubIata}-${route.arrivalIata}"
    val target = state.targetPrices[codeKey] ?: 0
    // v2.10: цель может считаться по сумме «туда+обратно»
    val targetRT = codeKey in state.targetRoundTrips
    var showTargetDialog by remember(codeKey) { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            // v2.5: тап по карточке открывает график истории цен направления
            .clickable { onOpenHistory(route) },
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Flight,
                            null,
                            Modifier.size(23.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "$hubName → ${route.arrivalName.ifBlank { route.arrivalIata }}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val w = state.weather[route.arrivalIata]
                    Text(
                        w?.let { "${it.icon} ${formatTemp(it.tempC)} · ${it.description}" }
                            ?: "Код: ${route.arrivalIata}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (w != null) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (w != null) FontWeight.Medium else FontWeight.Normal,
                    )
                }
                FavoriteStar("${route.hubIata}-${route.arrivalIata}", state, vm)
                cheapest?.let {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(
                                formatPrice(it.price),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            formatByn(it.price, state.bynPerRub)?.let { byn ->
                                Text(
                                    "≈ $byn",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                        .copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }

            // Прогноз выгодности по истории цен (v2.3): появляется после 3+ наблюдений.
            state.assessments[codeKey]?.let { a ->
                Spacer(Modifier.height(10.dp))
                PriceVerdictBadge(a)
            }

            // v2.7: целевая цена для избранных направлений — «🎯 дешевле N ₽» / «+ цель».
            if (codeKey in state.favorites) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.animateContentSize(),
                ) {
                    if (target > 0) {
                        Surface(
                            onClick = { showTargetDialog = true },
                            shape = RoundedCornerShape(50),
                            color = if (cheapest != null && cheapest.price <= target)
                                     MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = if (cheapest != null && cheapest.price <= target)
                                            "🎯 Цель достигнута · ${formatPrice(target)}${if (targetRT) " ↨" else ""}"
                                       else "🎯 Ждём цену ≤ ${formatPrice(target)}${if (targetRT) " ↨ туда+обратно" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        TextButton(
                            onClick = { vm.setTargetPrice(codeKey, 0) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("снять", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        TextButton(
                            onClick = { showTargetDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "🎯 задать целевую цену",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Цены по дням — горизонтальная лента «капсул»
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sorted.forEach { entry ->
                    val isMin = entry.price == cheapest?.price
                    val date = runCatching { LocalDate.parse(entry.depDate).format(dayFmt) }
                        .getOrDefault(entry.depDate)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isMin) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isMin) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                date,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                formatPrice(entry.price),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isMin) FontWeight.ExtraBold else FontWeight.SemiBold,
                            )
                            // v2.13: время вылета + прямой/стыковочный для лучшей цены дня
                            if (isMin) {
                                if (entry.depTimeVerified) entry.depTime?.let {
                                    Text(
                                        "✈ $it · ${if (entry.isDirect) "прямой" else "стыковочный"}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } ?: run {
                                    Text(
                                        if (entry.isDirect) "прямой" else "стыковочный",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            formatByn(entry.price, state.bynPerRub)?.let {
                                Text("≈ $it", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // v2.7: диалог задания целевой цены направления (v2.10: выбор «туда» / «туда+обратно»)
    if (showTargetDialog) {
        val pairPreview: Int? = cheapest?.price?.let { out ->
            state.returnPrices[codeKey]?.values?.minOfOrNull { it.price }?.let { out + it }
        }
        TargetPriceDialog(
            routeName = "${hubName} → ${route.arrivalName.ifBlank { route.arrivalIata }}",
            currentPrice = cheapest?.price,
            pairPrice = pairPreview,
            initialTarget = target,
            initialRoundTrip = targetRT,
            onDismiss = { showTargetDialog = false },
            onSave = { price, roundTrip ->
                vm.setTargetPrice(codeKey, price, roundTrip)
                showTargetDialog = false
            },
        )
    }
}

/**
 * Диалог «Целевая цена» (v2.7): для избранного направления можно задать сумму —
 * приложение пришлёт уведомление, когда минимальная цена станет ≤ цели.
 * v2.10: можно выбрать базу цели — «только туда» или сумму «туда+обратно».
 */
@Composable
private fun TargetPriceDialog(
    routeName: String,
    currentPrice: Int?,
    pairPrice: Int?,
    initialTarget: Int,
    initialRoundTrip: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Boolean) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(if (initialTarget > 0) initialTarget.toString() else "") }
    var roundTrip by rememberSaveable { mutableStateOf(initialRoundTrip) }
    val parsed = text.filter { it.isDigit() }.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 Целевая цена") },
        text = {
            Column {
                Text(
                    routeName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Пришлём уведомление, когда цена станет не выше указанной.\n" +
                        (currentPrice?.let { "Сейчас (туда): ${formatPrice(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // v2.10: выбор базы цели — «только туда» / «туда+обратно»
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "✈️ только туда", true to "↨ туда + обратно").forEach { (rt, label) ->
                        Surface(
                            onClick = { roundTrip = rt },
                            shape = RoundedCornerShape(50),
                            color = if (roundTrip == rt) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (roundTrip == rt) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (roundTrip) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pairPrice?.let {
                            "Сейчас туда+обратно ≈ ${formatPrice(it)}. " +
                                "Обратные цены обновляются в режиме «выходные»."
                        } ?: "Обратных цен пока нет — включите режим «выходные» с возвратом, " +
                              "чтобы сумма туда+обратно считалась. До тех пор цель проверяется по цене «только туда».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(if (roundTrip) "Сумма туда+обратно, ₽" else "Цена, ₽") },
                    leadingIcon = { Icon(Icons.Default.AttachMoney, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = if (roundTrip) listOf(20_000, 30_000, 40_000, 50_000)
                                  else listOf(10_000, 15_000, 20_000, 25_000)
                    presets.forEach { preset ->
                        Surface(
                            onClick = { text = preset.toString() },
                            shape = RoundedCornerShape(50),
                            color = if (parsed == preset) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                formatPrice(preset),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                val base = if (roundTrip) pairPrice else currentPrice
                base?.let { cur ->
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { text = ((cur * 9 / 10).coerceAtLeast(1000)).toString() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("на 10% ниже текущей (${formatPrice(cur * 9 / 10)})",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed, roundTrip) }, enabled = parsed > 0) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row {
                if (initialTarget > 0) {
                    TextButton(onClick = { onSave(0, false) }) { Text("Убрать цель") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * v2.12: экран «Мои цели» — все заданные целевые цены списком:
 * направление, режим (туда / туда+обратно), цель, последняя известная цена,
 * прогресс до цели; тап по строке открывает диалог изменения/снятия цели.
 */
@Composable
private fun TargetsScreen(
    state: UiState,
    vm: PobedaViewModel,
    onDismiss: () -> Unit,
) {
    val targets = state.targetPrices.entries.sortedBy { it.value }
    var editing by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 Мои цели (${targets.size})") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (targets.isEmpty()) {
                    Text(
                        "Целей пока нет.\nОтметьте направление ⭐ и нажмите «🎯 задать целевую цену» на карточке — уведомление придёт, когда цена станет не выше цели.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                targets.forEach { (code, target) ->
                    val rt = code in state.targetRoundTrips
                    // Последняя известная цена: из загруженных направлений или сохранённый снимок.
                    val currentOneWay: Int? = state.routes
                        .firstOrNull { "${it.hubIata}-${it.arrivalIata}" == code }
                        ?.cheapest?.price
                        ?: runCatching { vm.lastKnownPrice(code) }.getOrNull()
                    val returnMin = state.returnPrices[code]?.values?.minOfOrNull { it.price }
                    val currentPair = currentOneWay?.let { o -> returnMin?.let { o + it } }
                    val current = if (rt) currentPair else currentOneWay
                    val reached = current != null && current <= target
                    val gap = current?.let { it - target }

                    Surface(
                        onClick = { editing = code },
                        shape = RoundedCornerShape(16.dp),
                        color = if (reached) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    vm.arrivalNameFor(code),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (rt) "↨ туда+обратно" else "✈️ туда",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "цель ${formatPrice(target)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(10.dp))
                                when {
                                    reached -> Text(
                                        "✅ ${formatPrice(current!!)} — достигнута",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    current != null -> Text(
                                        "сейчас ${formatPrice(current)} · осталось −${formatPrice(gap!!)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> Text(
                                        "цен пока нет",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Прогресс: насколько текущая цена ближе к цели относительно её пика
                            if (current != null && !reached && current > 0) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = (target.toFloat() / current.toFloat()).coerceIn(0f, 1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )

    // Диалог редактирования цели прямо из списка
    editing?.let { code ->
        val route = state.routes.firstOrNull { "${it.hubIata}-${it.arrivalIata}" == code }
        TargetPriceDialog(
            routeName = vm.arrivalNameFor(code),
            currentPrice = route?.cheapest?.price,
            pairPrice = route?.cheapest?.price?.let { o ->
                state.returnPrices[code]?.values?.minOfOrNull { it.price }?.let { o + it }
            },
            initialTarget = state.targetPrices[code] ?: 0,
            initialRoundTrip = code in state.targetRoundTrips,
            onDismiss = { editing = null },
            onSave = { price, roundTrip ->
                vm.setTargetPrice(code, price, roundTrip)
                editing = null
            },
        )
    }
}

private fun formatPrice(price: Int): String =
    String.format(Locale("ru"), "%,d", price).replace(',', ' ') + " ₽"

/**
 * Диалог «История цен» (v2.5): открывается тапом по карточке направления.
 * Показывает линейный график накопленных минимальных цен по дням, статистику
 * (мин/медиана/макс) и текущую цену. Если наблюдений мало — просит обновлять
 * приложение несколько дней.
 */
@Composable
private fun PriceHistoryDialog(
    route: PobedaRepository.RoutePrices,
    vm: PobedaViewModel,
    state: UiState,
    onDismiss: () -> Unit,
) {
    val history = remember(route.hubIata, route.arrivalIata, state.lastUpdated) {
        vm.historyFor(route.hubIata, route.arrivalIata)
    }
    val hubName = PobedaRepository.HUBS.first { it.iata == route.hubIata }.name
    val destName = route.arrivalName.ifBlank { route.arrivalIata }
    val shortFmt = remember { DateTimeFormatter.ofPattern("d.MM", RU) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                "История цен: $hubName → $destName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (history.size < 2) {
                    Text(
                        "📈 Накоплено наблюдений: ${history.size}.\n\n" +
                            "Приложение запоминает минимальную цену каждый день при " +
                            "обновлении списка (в т.ч. в фоне ~раз в час) — " +
                            "и через несколько дней здесь появится график.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val prices = history.map { it.second }
                    val min = prices.min()
                    val max = prices.max()
                    val sortedP = prices.sorted()
                    val median = if (sortedP.size % 2 == 1) sortedP[sortedP.size / 2]
                    else (sortedP[sortedP.size / 2 - 1] + sortedP[sortedP.size / 2]) / 2
                    val current = route.cheapest?.price

                    PriceChart(
                        points = history,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    // подписи первой/последней даты
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            history.first().first.format(shortFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            history.last().first.format(shortFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HistoryStat("Минимум", formatPrice(min), Color(0xFF1B5E20))
                        HistoryStat("Медиана", formatPrice(median), MaterialTheme.colorScheme.onSurface)
                        HistoryStat("Максимум", formatPrice(max), Color(0xFFB71C1C))
                    }
                    current?.let { c ->
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Сейчас",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                formatPrice(c),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = when {
                                    c <= min -> Color(0xFF1B5E20)
                                    c >= max -> Color(0xFFB71C1C)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        formatByn(c, state.bynPerRub)?.let { byn ->
                            Text(
                                "≈ $byn",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.assessments["${route.hubIata}-${route.arrivalIata}"]?.let { a ->
                        Spacer(Modifier.height(10.dp))
                        PriceVerdictBadge(a)
                    }
                    Text(
                        "Наблюдений: ${history.size} · максимум храним ${ru.pobedamonitor.data.PriceHistoryRepository.MAX_DAYS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun HistoryStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/**
 * Простой Canvas-график линии цен: точки (дата, цена), старые -> новые.
 * Ось Y масштабируется по min/max с отступом; под линией — полупрозрачная
 * заливка, на последней точке — акцентный маркер.
 */
@Composable
private fun PriceChart(
    points: List<Pair<LocalDate, Int>>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.secondary

    val xs = remember(points) {
        // нормализуем позиции точек по индексу (дни могут идти с пропусками)
        points.indices.toList()
    }

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val padTop = 14.dp.toPx()
        val padBottom = 14.dp.toPx()
        val h = size.height - padTop - padBottom
        val lo = points.minOf { it.second }.toDouble()
        val hi = points.maxOf { it.second }.toDouble()
        val span = ((hi - lo) * 0.15).coerceAtLeast(1.0)
        val yMin = lo - span
        val yMax = hi + span

        fun xAt(i: Int): Float =
            if (xs.size == 1) size.width / 2f
            else size.width * i.toFloat() / (xs.size - 1)

        fun yAt(v: Int): Float =
            padTop + h.toFloat() * (1f - (v - yMin) / (yMax - yMin)).toFloat()

        // горизонтальные линии сетки (3 деления)
        for (k in 0..2) {
            val yy = padTop + h * k / 2f
            drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
        }

        val path = Path().apply {
            points.forEachIndexed { i, (_, price) ->
                val p = Offset(xAt(i), yAt(price))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        // заливка под линией
        val fillPath = Path().apply {
            addPath(path)
            lineTo(xAt(points.lastIndex), size.height - padBottom)
            lineTo(xAt(0), size.height - padBottom)
            close()
        }
        drawPath(fillPath, fillColor)
        drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        // точки наблюдений
        points.forEachIndexed { i, (_, price) ->
            drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(xAt(i), yAt(price)))
        }
        // маркер последней точки
        val last = Offset(xAt(points.lastIndex), yAt(points.last().second))
        drawCircle(markerColor, radius = 5.dp.toPx(), center = last)
        drawCircle(androidx.compose.ui.graphics.Color.White, radius = 2.5.dp.toPx(), center = last)
    }
}

/**
 * Бейдж прогноза выгодности (v2.3): сравнивает текущую минимальную цену
 * с медианой предыдущих наблюдений истории цен.
 */
@Composable
private fun PriceVerdictBadge(a: ru.pobedamonitor.data.PriceHistoryRepository.Assessment) {
    val v = a.verdict
    val container = when (v) {
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.GOOD -> Color(0xFF1B5E20).copy(alpha = 0.14f)
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.BAD -> Color(0xFFB71C1C).copy(alpha = 0.12f)
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (v) {
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.GOOD -> Color(0xFF1B5E20)
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.BAD -> Color(0xFFB71C1C)
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (v) {
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.GOOD -> "▼"
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.BAD -> "▲"
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.NEUTRAL -> "≈"
    }
    val title = when (v) {
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.GOOD -> "Выгодно: ниже обычной цены"
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.BAD -> "Дорого: выше обычной цены"
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.NEUTRAL -> "Обычная цена"
    }
    val absDiff = kotlin.math.abs(a.diffPercent)
    val subtitle = when (v) {
        ru.pobedamonitor.data.PriceHistoryRepository.Verdict.NEUTRAL ->
            "медиана ${formatPrice(a.median)}"
        else ->
            "$absDiff% от медианы ${formatPrice(a.median)}"
    }
    Surface(color = container, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon, color = content, fontWeight = FontWeight.ExtraBold)
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f))
            }
        }
    }
}

/** Температура в формате «+18°C» / «−5°C». */
private fun formatTemp(tempC: Double): String {
    val t = tempC.roundToInt()
    val sign = if (t > 0) "+" else ""
    return "$sign${t}°C".replace('-', '−')
}

/** Конвертация цены в белорусские рубли по курсу [bynPerRub] (RUB -> BYN). */
private fun formatByn(price: Int, bynPerRub: Double?): String? {
    if (bynPerRub == null || bynPerRub <= 0) return null
    val byn = price * bynPerRub
    return when {
        byn >= 100 -> String.format(RU, "%,d", byn.toInt()).replace(',', ' ') + " Br"
        else -> String.format(RU, "%.2f", byn).replace('.', ',') + " Br"
    }
}
