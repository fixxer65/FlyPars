package ru.pobedamonitor.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.pobedamonitor.data.FavoritesRepository
import ru.pobedamonitor.data.PobedaRepository
import ru.pobedamonitor.data.PriceHistoryRepository
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * v2.8: фоновая проверка цен (WorkManager); v2.11: интервал ~1 час.
 *
 * Загружает цены по избранным направлениям даже когда приложение закрыто,
 * пополняет историю цен и шлёт уведомления («падение» / «цель достигнута»).
 */
class PriceCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val favorites = FavoritesRepository(applicationContext)
        // Смысл есть только при включённых уведомлениях или заданных целях.
        if (!favorites.notificationsEnabled && favorites.allTargetPrices().isEmpty()) {
            return@withContext Result.success()
        }

        val codes = favorites.load()
        if (codes.isEmpty()) return@withContext Result.success()

        val repository = PobedaRepository()
        val history = PriceHistoryRepository(applicationContext)
        val notifier = PriceDropNotifier(applicationContext)

        val today = LocalDate.now()
        val from = today.plusDays(1)          // ближайшие билеты обычно не продаются
        val to = today.plusDays(BACKFILL_DAYS)

        // v2.14: дни недели уведомлений (режим «выходных») — берём последние
        // сохранённые из UI; пустое множество = обычный режим «все даты».
        val outDays = favorites.notifyOutboundDays
        val returnEnabled = favorites.notifyReturnEnabled
        val retDays = favorites.notifyReturnDays
        val outFilter: ((LocalDate) -> Boolean)? =
            if (outDays.isNotEmpty()) { d -> d.dayOfWeek in outDays } else null
        val retFilter: ((LocalDate) -> Boolean)? =
            if (returnEnabled && retDays.isNotEmpty()) { d -> d.dayOfWeek in retDays } else null

        // Группируем избранные по хабам — API отдаёт все направления хаба одним запросом на дату.
        val byHub = codes.groupBy { it.substringBefore('-') }
        val routes = mutableListOf<PobedaRepository.RoutePrices>()
        for ((hub, subset) in byHub) {
            val wanted = subset.mapNotNull { it.substringAfter('-', "").ifBlank { null } }.toSet()
            if (wanted.isEmpty()) continue
            val res = runCatching { repository.fetchPrices(from = from, to = to, hubs = listOf(hub)) }
                .getOrNull() ?: continue
            routes += res.routes.filter { it.arrivalIata in wanted }
        }

        if (routes.isEmpty()) return@withContext Result.retry()

        // Пополняем историю дневных минимумов (для графика и прогноза).
        val keyFor: (PobedaRepository.RoutePrices) -> String = { "${it.hubIata}-${it.arrivalIata}" }
        val todayPrices = routes.mapNotNull { r ->
            (if (outFilter != null) r.cheapestFiltered(outFilter) else r.cheapest)
                ?.price?.let { keyFor(r) to it }
        }.toMap()
        if (todayPrices.isNotEmpty()) history.recordAll(todayPrices)

        // Уведомления: тот же движок, что и в интерфейсе (v2.4/v2.6/v2.7).
        // v2.10: если хотя бы у одной цели режим «туда+обратно» — догружаем обратные цены.
        var returnPrices: Map<String, Map<String, PobedaRepository.PriceEntry>> = emptyMap()
        val rtTargets = favorites.allTargetRoundTrips()
        if (favorites.notifyRoundTrip || rtTargets.isNotEmpty()) {
            val needRt = routes.filter { r ->
                favorites.notifyRoundTrip || keyFor(r) in rtTargets
            }
            val dates = ArrayList<LocalDate>()
            var d = today.plusDays(1)
            while (d <= today.plusDays(BACKFILL_DAYS)) { dates.add(d); d = d.plusDays(1) }
            returnPrices = coroutineScope {
                needRt.map { route ->
                    async(Dispatchers.IO) {
                        keyFor(route) to runCatching {
                            repository.fetchReturnPrices(
                                hubIata = route.hubIata,
                                arrivalIata = route.arrivalIata,
                                dates = dates,
                            )
                        }.getOrDefault(emptyMap())
                    }
                }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
            }
        }
        runCatching { notifier.onPricesLoaded(routes, keyFor, returnPrices, outFilter, retFilter) }
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "hourly_price_check"
        private const val BACKFILL_DAYS = 20L

        /**
         * Ставит фоновую проверку с сохранением уже выбранного интервала (v2.12).
         * Если задание ещё не существовало — создаётся с интервалом по умолчанию (1 ч).
         */
        fun schedule(context: Context) {
            val saved = FavoritesRepository(context).checkIntervalHours
            schedule(context, saved)
        }

        /**
         * Ставит/обновляет периодическую проверку цен с указанным интервалом в часах
         * (v2.12: пользователь может выбрать 1 / 6 / 24; минимум WorkManager — 1 час).
         */
        fun schedule(context: Context, hours: Int) {
            val h = hours.coerceIn(
                FavoritesRepository.MIN_CHECK_HOURS,
                FavoritesRepository.MAX_CHECK_HOURS,
            )
            val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(h.toLong(), TimeUnit.HOURS)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            // REPLACE: старые задания с другим интервалом нужно пересоздать заново
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
