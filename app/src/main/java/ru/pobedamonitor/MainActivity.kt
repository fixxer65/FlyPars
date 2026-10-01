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
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            RateAndUpdatedChip(state)
            FilterCard(state = state, vm = vm)

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
}

/** Компактная плашка: курс BYN + время обновления. */
@Composable
private fun RateAndUpdatedChip(state: UiState) {
    val rateText = when {
        state.bynPerRub != null -> {
            val src = state.rateSource?.let { " · $it" } ?: ""
            "Курс: 1 RUB = ${String.format(RU, "%.4f", state.bynPerRub)} BYN$src"
        }
        state.rateError != null -> "Курс BYN недоступен"
        else -> "Загружаем курс BYN…"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 1.dp,
        ) {
            Text(
                rateText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        state.lastUpdated?.let {
            Text(
                "обновлено $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Все элементы управления аккуратно собраны в одной «тонированной» карточке.
 *  Карточка сворачивается по тапу на заголовок, чтобы оставить больше места ценам. */
@Composable
private fun FilterCard(state: UiState, vm: PobedaViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // v2.9: ограничиваем высоту развёрнутой карточки ~70% экрана (заголовок крупный TopAppBar
    // схлопывается при прокрутке списка, так что запас по высоте есть). Внутри — verticalScroll.
    val maxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.70f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // v2.9: высота развёрнутой карточки ограничена (высота экрана минус шапка,
            // курс и отступы) — внутренний скролл параметров включается ровно тогда,
            // когда они не помещаются, и никогда не «съедает» место у списка цен.
            .heightIn(max = maxHeight)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Заголовок-переключатель: всегда виден, показывает суть выбранных фильтров
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Tune,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Параметры поиска",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    filterSummary(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                if (expanded) "Свернуть" else "Развернуть",
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            // v2.9: список параметров вырос (режимы, избранное, хабы, пунктры, сортировка,
            // даты выходных с обратными рейсами, уведомления) — делаем его прокручиваемым,
            // иначе в режиме «туда и обратно» нижние элементы не достать скроллом страницы.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                ModeTabs(state, vm)
                Spacer(Modifier.height(10.dp))
                FavoritesTabs(state, vm)
                Spacer(Modifier.height(10.dp))
                HubChips(state, vm)
                Spacer(Modifier.height(8.dp))
                DestinationPicker(state, vm)
                Spacer(Modifier.height(6.dp))
                SortTabs(state, vm)
                Spacer(Modifier.height(6.dp))
                if (state.mode == SearchMode.ALL_DAYS) {
                    AllDaysControls(state, vm)
                } else {
                    WeekendsControls(state, vm)
                }
                Spacer(Modifier.height(6.dp))
                NotificationsRow(state, vm)
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
                    "Только для ⭐ избранных направлений · не чаще раза в 3 часа",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // v2.8: WorkManager проверяет цены и шлёт уведомления даже с закрытым приложением.
                // v2.11: интервал сокращён до ~1 часа.
                Text(
                    "Работает в фоне ~раз в час (без открытия приложения)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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

            // v2.6: база цены — «только туда» или «туда и обратно».
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Считать цену:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = !state.notifyRoundTrip,
                        onClick = { vm.setNotifyRoundTrip(false) },
                        label = { Text("только туда", maxLines = 1) },
                    )
                    FilterChip(
                        selected = state.notifyRoundTrip,
                        onClick = { vm.setNotifyRoundTrip(true) },
                        label = { Text("туда + обратно", maxLines = 1) },
                    )
                }
            }
            if (state.notifyRoundTrip) {
                Text(
                    "Обратные цены подгружаются в режиме выходных; без них следим за ценой «только туда».",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            // v2.6: режим порога — проценты или конкретная сумма (₽).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Реагировать на падение:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT,
                        onClick = { vm.setNotifyMode(ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT) },
                        label = { Text("в %", maxLines = 1) },
                    )
                    FilterChip(
                        selected = state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_AMOUNT,
                        onClick = { vm.setNotifyMode(ru.pobedamonitor.data.FavoritesRepository.MODE_AMOUNT) },
                        label = { Text("на сумму ₽", maxLines = 1) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (state.notifyMode == ru.pobedamonitor.data.FavoritesRepository.MODE_PERCENT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Порог: −${state.dropThresholdPercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(74.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "₽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1000, 2000, 3000, 5000).forEach { preset ->
                        AssistChip(
                            onClick = { vm.setDropAmountRub(preset) },
                            label = { Text(String.format(Locale("ru"), "%,d", preset).replace(',', ' ') + " ₽") },
                        )
                    }
                }
            }
        }
    }
}

/** Короткое описание текущих фильтров для свёрнутого состояния. */
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
            append(state.month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", RU)))
        }
    }
}

private fun UiState.modeLabel(): String = when (mode) {
    SearchMode.ALL_DAYS -> "Все дни"
    SearchMode.WEEKENDS -> "Выходные"
}

/** Красивый переключатель режимов в стиле сегментированных вкладок. */
@Composable
private fun ModeTabs(state: UiState, vm: PobedaViewModel) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchMode.entries.forEach { mode ->
                val selected = state.mode == mode
                val text = when (mode) {
                    SearchMode.ALL_DAYS -> "📅  Все дни"
                    SearchMode.WEEKENDS -> "🌤  Выходные"
                }
                Surface(
                    onClick = {
                        if (!selected) { vm.setMode(mode); vm.refresh() }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelLarge,
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

/** Переключатель «Все / Избранные» — фильтр списка направлений. */
@Composable
private fun FavoritesTabs(state: UiState, vm: PobedaViewModel) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            data class Opt(val filter: ListFilter, val label: String)
            listOf(
                Opt(ListFilter.ALL, "🌐  Все направления"),
                Opt(ListFilter.FAVORITES, "⭐  Избранные (${state.favorites.size})"),
            ).forEach { opt ->
                val selected = state.listFilter == opt.filter
                Surface(
                    onClick = { vm.setListFilter(opt.filter) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
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
@Composable
private fun HubChips(state: UiState, vm: PobedaViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Откуда:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PobedaRepository.HUBS.forEach { hub ->
            val selected = hub.iata in state.hubsSelected
            FilterChip(
                selected = selected,
                onClick = { vm.toggleHub(hub.iata); vm.refresh() },
                label = { Text(hub.name, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Outlined.AirplanemodeActive, null, Modifier.size(18.dp))
                    }
                },
                shape = RoundedCornerShape(50),
            )
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
        OutlinedTextField(
            value = when {
                state.destinationsSelected.isEmpty() -> "все направления"
                state.destinationsSelected.size == 1 -> {
                    val code = state.destinationsSelected.first()
                    "${Airports.nameOf(code)} ($code)"
                }
                else -> "выбрано ${state.destinationsSelected.size}"
            },
            onValueChange = {},
            readOnly = true,
            enabled = false, // сам клик перехватывается прозрачным Box'ом ниже
            label = { Text("Куда") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.destinationsSelected.isNotEmpty()) {
                    TextButton(onClick = vm::clearDestinations) { Text("Сброс ✕") }
                } else {
                    Icon(Icons.Default.ExpandMore, null)
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableBox { expanded = true },
        )

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
@Composable
private fun AllDaysControls(state: UiState, vm: PobedaViewModel) {
    val context = LocalContext.current
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", RU) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = {
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
        }, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(state.fromDate.format(dateFmt), fontWeight = FontWeight.Medium)
        }

        listOf(7, 14, 30).forEach { days ->
            FilterChip(
                selected = state.daysCount == days,
                onClick = { vm.setDaysCount(days); vm.refresh() },
                label = { Text("${days} дн.") },
                shape = RoundedCornerShape(50),
            )
        }
    }
}

/** Режим «выходных»: месяц + дни вылета «туда» + опция обратных билетов. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekendsControls(state: UiState, vm: PobedaViewModel) {
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", RU) }
    var monthPickerOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // Навигатор месяца: ‹ декабрь 2026 ›
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.shiftMonth(-1); vm.refresh() }) {
                    Icon(Icons.Default.ChevronRight, "Предыдущий месяц",
                        modifier = Modifier.rotate(180f))
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.month.atDay(1).format(monthFmt)
                            .replaceFirstChar { it.uppercase(RU) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "выберите месяц поиска",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.shiftMonth(+1); vm.refresh() }) {
                    Icon(Icons.Default.ChevronRight, "Следующий месяц")
                }
                IconButton(onClick = { monthPickerOpen = true }) {
                    Icon(Icons.Default.CalendarMonth, "Выбрать месяц")
                }
            }
        }

        // Нормальный диалог выбора месяца (Compose DatePicker: год + сетка месяцев)
        if (monthPickerOpen) {
            val initMillis = remember(state.month) {
                state.month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli()
            }
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = initMillis)
            DatePickerDialog(
                onDismissRequest = { monthPickerOpen = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val d = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            vm.setMonth(java.time.YearMonth.of(d.year, d.monthValue))
                            vm.refresh()
                        }
                        monthPickerOpen = false
                    }) { Text("ОК") }
                },
                dismissButton = {
                    TextButton(onClick = { monthPickerOpen = false }) { Text("Отмена") }
                },
            ) {
                DatePicker(
                    state = pickerState,
                    showModeToggle = true,
                    title = {
                        Text(
                            "Месяц поиска",
                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                )
            }
        }

        DayRow(
            caption = "✈ Туда:",
            selected = state.outboundDays,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = { day -> vm.toggleWeekendDay(day); vm.refresh() },
        )

        // Переключатель обратных билетов — красивый Switch вместо чекбокса
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (state.returnEnabled) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    null,
                    tint = if (state.returnEnabled) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Обратные билеты",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.returnEnabled) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.returnEnabled,
                    onCheckedChange = { vm.toggleReturnEnabled(it); vm.refresh() },
                )
            }
        }

        if (state.returnEnabled) {
            DayRow(
                caption = "↵ Обратно:",
                selected = state.returnDays,
                accent = MaterialTheme.colorScheme.tertiary,
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
                                Text(
                                    "✈ ${combo.depDate.format(longFmt).replaceFirstChar { it.uppercase(RU) }}" +
                                        "   →   ↵ " +
                                        (if (combo.approxReturn) "≈" else "") +
                                        combo.retDate.format(longFmt).replaceFirstChar { it.uppercase(RU) },
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
