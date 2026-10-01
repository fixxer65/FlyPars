package ru.pobedamonitor.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.pobedamonitor.MainActivity
import ru.pobedamonitor.R
import ru.pobedamonitor.data.FavoritesRepository
import ru.pobedamonitor.data.PobedaRepository
import java.util.Locale

/**
 * Уведомления «цена упала» (v2.4).
 *
 * Срабатывают после каждой успешной загрузки цен, если ИЗБРАННОЕ направление
 * подешевело по сравнению с последней известной ценой на порог и больше.
 * Не чаще одного уведомления на направление в сутки.
 */
class PriceDropNotifier(private val context: Context) {

    private val favorites = FavoritesRepository(context)

    /**
     * Вызывается из ViewModel после обновления цен.
     * [routes] — загруженные направления; [keyFor] возвращает ключ "hub-arrival".
     */
    fun onPricesLoaded(
        routes: List<PobedaRepository.RoutePrices>,
        keyFor: (PobedaRepository.RoutePrices) -> String,
    ) {
        // Снимаем предыдущие цены ДО записи новых, затем обновляем хранилище.
        val previousPrices = routes.associate { r ->
            keyFor(r) to favorites.getLastKnownPrice(keyFor(r))
        }
        routes.forEach { r ->
            val price = r.cheapest?.price ?: return@forEach
            favorites.saveLastKnownPrice(keyFor(r), price)
        }

        if (!favorites.notificationsEnabled) return
        if (!hasNotificationPermission()) return

        val threshold = favorites.dropThresholdPercent
        val now = System.currentTimeMillis()
        val dayMillis = 24L * 60 * 60 * 1000
        val favoriteCodes = favorites.load()

        routes.forEach { route ->
            val code = keyFor(route)
            if (code !in favoriteCodes) return@forEach // только избранные
            val current = route.cheapest?.price ?: return@forEach
            val previous = previousPrices[code] ?: return@forEach
            if (previous <= 0 || current >= previous) return@forEach

            val dropPercent = ((previous - current) * 100.0 / previous).toInt()
            if (dropPercent < threshold) return@forEach
            // анти-спам: не чаще раза в сутки на направление
            if (now - favorites.getLastNotifyTime(code) < dayMillis) return@forEach

            favorites.setLastNotifyTime(code, now)
            notifyOne(route, code, previous, current, dropPercent)
        }
    }

    private fun notifyOne(
        route: PobedaRepository.RoutePrices,
        code: String,
        previous: Int,
        current: Int,
        dropPercent: Int,
    ) {
        ensureChannel()
        val name = route.arrivalName.ifBlank { route.arrivalIata }
        val title = "💸 $name: цена упала на $dropPercent%"
        val text = "${formatPrice(previous)} → ${formatPrice(current)}"

        val tap = PendingIntent.getActivity(
            context, code.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_FAVORITES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$title\n$text")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify("drop-$code".hashCode(), notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Падение цен",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Уведомления, когда цена избранного направления снижается"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "price_drops"
        const val EXTRA_OPEN_FAVORITES = "open_favorites"

        private fun formatPrice(price: Int): String =
            String.format(Locale("ru"), "%,d", price).replace(',', ' ') + " ₽"
    }
}
