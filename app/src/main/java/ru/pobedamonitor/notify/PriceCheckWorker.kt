package ru.pobedamonitor.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.pobedamonitor.data.FavoritesRepository
import ru.pobedamonitor.data.PobedaRepository
import ru.pobedamonitor.data.PriceHistoryRepository
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * v2.8: фоновая проверка цен (WorkManager, ~раз в сутки).
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
            r.cheapest?.price?.let { keyFor(r) to it }
        }.toMap()
        if (todayPrices.isNotEmpty()) history.recordAll(todayPrices)

        // Уведомления: тот же движок, что и в интерфейсе (v2.4/v2.6/v2.7).
        runCatching { notifier.onPricesLoaded(routes, keyFor) }
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_price_check"
        private const val BACKFILL_DAYS = 20L

        /** Ставит/обновляет ежедневную фоновую проверку. Вызывается из UI при включении уведомлений. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
