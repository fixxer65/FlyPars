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
     * [routes] — загруженные направления; [keyFor] возвращает ключ "hub-arrival";
     * [returnPrices] — обратные цены (дата -> entry) по тому же ключу (v2.6, для режима «туда и обратно»).
     */
    fun onPricesLoaded(
        routes: List<PobedaRepository.RoutePrices>,
        keyFor: (PobedaRepository.RoutePrices) -> String,
        returnPrices: Map<String, Map<String, PobedaRepository.PriceEntry>> = emptyMap(),
    ) {
        val roundTrip = favorites.notifyRoundTrip

        // Лучшая цена направления с учётом выбранного режима («туда» или сумма пары).
        fun bestPrice(route: PobedaRepository.RoutePrices): Int? {
            val out = route.cheapest?.price ?: return null
            if (!roundTrip) return out
            val retMin = returnPrices[keyFor(route)]?.values?.minOfOrNull { it.price } ?: return out
            return out + retMin
        }

        // v2.10: сумма «туда+обратно» для целевой цены независимо от глобального режима падений.
        fun pairPrice(
            route: PobedaRepository.RoutePrices,
            returns: Map<String, Map<String, PobedaRepository.PriceEntry>>,
            oneWay: Int,
        ): Int {
            val retMin = returns[keyFor(route)]?.values?.minOfOrNull { it.price } ?: return oneWay
            return oneWay + retMin
        }

        // Снимаем предыдущие цены ДО записи новых, затем обновляем хранилище (оба ключа: «туда» и «пара»).
        val previousPrices = routes.flatMap { r ->
            listOf(priceKey(keyFor(r), false), priceKey(keyFor(r), true))
                .associateWith { favorites.getLastKnownPrice(it) }
        }
        routes.forEach { r ->
            val oneWay = r.cheapest?.price ?: return@forEach
            favorites.saveLastKnownPrice(priceKey(keyFor(r), false), oneWay)
            favorites.saveLastKnownPrice(priceKey(keyFor(r), true), pairPrice(r, returnPrices, oneWay))
        }

        if (!favorites.notificationsEnabled) return
        if (!hasNotificationPermission()) return

        val modeAmount = favorites.notifyMode == FavoritesRepository.MODE_AMOUNT
        val thresholdPercent = favorites.dropThresholdPercent
        val thresholdAmount = favorites.dropThresholdAmount
        if (modeAmount && thresholdAmount <= 0) return // сумма не задана — не шлём
        val now = System.currentTimeMillis()
        val dayMillis = 24L * 60 * 60 * 1000
        val favoriteCodes = favorites.load()

        routes.forEach { route ->
            val code = keyFor(route)
            if (code !in favoriteCodes) return@forEach // только избранные
            val current = bestPrice(route) ?: return@forEach

            // ---- v2.7: уведомление при достижении ЦЕЛЕВОЙ цены направления ----
            // v2.10: цель может быть задана на «только туда» или на сумму «туда+обратно» — независимо от режима падений.
            val target = favorites.getTargetPrice(code)
            if (target > 0) {
                val targetRT = favorites.isTargetRoundTrip(code)
                val currentForTarget = if (targetRT) pairPrice(route, returnPrices, current) else current
                val prevKey = priceKey(code, targetRT)
                if (currentForTarget <= target) {
                    // не спамим: повторно только если цена снова выросла выше цели и затем упала
                    val wasAboveTarget = previousPrices[prevKey]?.let { it > target } ?: true
                    if (wasAboveTarget && now - favorites.getLastTargetNotifyTime(code) >= dayMillis) {
                        favorites.setLastTargetNotifyTime(code, now)
                        notifyTarget(route, code, currentForTarget, target, targetRT)
                        return@forEach
                    }
                }
            }

            val previous = previousPrices[priceKey(code, roundTrip)] ?: return@forEach
            if (previous <= 0 || current >= previous) return@forEach

            val dropRub = previous - current
            val dropPercent = (dropRub * 100.0 / previous).toInt()
            val triggered =
                if (modeAmount) dropRub >= thresholdAmount else dropPercent >= thresholdPercent
            if (!triggered) return@forEach
            // анти-спам: не чаще раза в сутки на направление
            if (now - favorites.getLastNotifyTime(code) < dayMillis) return@forEach

            favorites.setLastNotifyTime(code, now)
            notifyOne(route, code, previous, current, dropPercent, dropRub, modeAmount, roundTrip)
        }
    }

    /** Ключ хранилища последней цены зависит от режима — чтобы %/₽ и «туда»/«пара» не смешивались. */
    private fun priceKey(code: String, roundTrip: Boolean): String =
        if (roundTrip) "$code#rt" else code

    /** v2.7: уведомление «достигнута целевая цена». */
    private fun notifyTarget(
        route: PobedaRepository.RoutePrices,
        code: String,
        current: Int,
        target: Int,
        roundTrip: Boolean,
    ) {
        ensureChannel()
        val name = route.arrivalName.ifBlank { route.arrivalIata }
        val rtLabel = if (roundTrip) " (туда+обратно)" else ""
        postNotification(
            tagId = "target-$code".hashCode(),
            title = "🎯 $name$rtLabel: цель достигнута!",
            text = "Цена ${formatPrice(current)} — ниже вашей цели ${formatPrice(target)}. Не упустите!",
            channel = CHANNEL_ID,
        )
    }

    private fun notifyOne(
        route: PobedaRepository.RoutePrices,
        code: String,
        previous: Int,
        current: Int,
        dropPercent: Int,
        dropRub: Int,
        modeAmount: Boolean,
        roundTrip: Boolean,
    ) {
        ensureChannel()
        val name = route.arrivalName.ifBlank { route.arrivalIata }
        val rtLabel = if (roundTrip) " (туда+обратно)" else ""
        val title = if (modeAmount) {
            "💸 $name$rtLabel: подешевело на ${formatPrice(dropRub)}"
        } else {
            "💸 $name$rtLabel: цена упала на $dropPercent%"
        }
        val text = "${formatPrice(previous)} → ${formatPrice(current)}"

        postNotification("drop-$code".hashCode(), title, text, CHANNEL_ID, tapRequestCode = code.hashCode())
    }

    /** Общий конструктор и отправка уведомления (v2.7: переиспользуется для «цели»). */
    private fun postNotification(tagId: Int, title: String, text: String, channel: String, tapRequestCode: Int = 0) {
        val tap = PendingIntent.getActivity(
            context, tapRequestCode,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_FAVORITES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
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
        manager.notify(tagId, notification)
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
