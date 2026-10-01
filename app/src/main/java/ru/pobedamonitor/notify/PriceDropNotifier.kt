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
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
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
        /**
         * v2.14: предикат «разрешённой даты» для уведомлений «туда». В режиме
         * «выходных» UI/воркер передают фильтр по выбранным дням недели вылета —
         * уведомления считаются только по датам из выборки пользователя.
         * null = учитывать все загруженные даты (обычный режим).
         */
        dateFilter: ((LocalDate) -> Boolean)? = null,
        /** v2.14: то же для обратных дат (выбранные дни возврата). null = все. */
        returnDateFilter: ((LocalDate) -> Boolean)? = null,
    ) {
        val roundTrip = favorites.notifyRoundTrip

        /** v2.14: лучшая цена «туда» с учётом фильтра дней, если он задан. */
        fun routeBest(route: PobedaRepository.RoutePrices): PobedaRepository.PriceEntry? =
            if (dateFilter != null) route.cheapestFiltered(dateFilter) else route.cheapest

        /** v2.14: минимальная обратная цена с учётом фильтра дней возврата. */
        fun retMinOf(code: String): Int? =
            returnPrices[code]?.values
                ?.filter { e ->
                    returnDateFilter == null || runCatching {
                        returnDateFilter(LocalDate.parse(e.depDate))
                    }.getOrDefault(false)
                }?.minOfOrNull { it.price }

        // Лучшая цена направления с учётом выбранного режима («туда» или сумма пары).
        fun bestPrice(route: PobedaRepository.RoutePrices): Int? {
            val out = routeBest(route)?.price ?: return null
            if (!roundTrip) return out
            val retMin = retMinOf(keyFor(route)) ?: return out
            return out + retMin
        }

        // v2.10: сумма «туда+обратно» для целевой цены независимо от глобального режима падений.
        fun pairPrice(
            route: PobedaRepository.RoutePrices,
            returns: Map<String, Map<String, PobedaRepository.PriceEntry>>,
            oneWay: Int,
        ): Int {
            val retMin = retMinOf(keyFor(route)) ?: return oneWay
            return oneWay + retMin
        }

        // Снимаем предыдущие цены ДО записи новых, затем обновляем хранилище (оба ключа: «туда» и «пара»).
        val previousPrices = mutableMapOf<String, Int?>()
        routes.forEach { r ->
            previousPrices[priceKey(keyFor(r), false)] = favorites.getLastKnownPrice(priceKey(keyFor(r), false))
            previousPrices[priceKey(keyFor(r), true)] = favorites.getLastKnownPrice(priceKey(keyFor(r), true))
        }
        routes.forEach { r ->
            val oneWay = routeBest(r)?.price ?: return@forEach
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
        // v2.11: фоновая проверка теперь ~раз в час — анти-спам «падений» сокращён до 3 часов,
        // чтобы реальное резкое снижение не терялось до следующего дня.
        val dropCooldownMillis = 3L * 60 * 60 * 1000
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
                val oneWay = routeBest(route)?.price
                val currentForTarget = if (oneWay != null && targetRT)
                    pairPrice(route, returnPrices, oneWay) else current
                val prevKey = priceKey(code, targetRT)
                if (currentForTarget <= target) {
                    // не спамим: повторно только если цена снова выросла выше цели и затем упала
                    val wasAboveTarget = previousPrices[prevKey]?.let { it > target } ?: true
                    val lastNotify = favorites.getLastTargetNotifyTime(code)
                    // v2.11: проверка теперь ~раз в час — дополнительно разрешаем «продержалась ниже цели»
                    // напоминание не чаще раза в сутки, иначе ждём нового пересечения вниз.
                    val heldBelowTooLong = now - lastNotify >= dayMillis
                    if ((wasAboveTarget || heldBelowTooLong) && now - lastNotify >= 3L * 60 * 60 * 1000) {
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
            // анти-спам: не чаще раза в 3 часа на направление (v2.11)
            if (now - favorites.getLastNotifyTime(code) < dropCooldownMillis) return@forEach

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
            details = flightDetails(route),
        )
    }

    /** v2.13: детали лучшего тарифа направления — дата/время вылета, прямой или стыковочный. */
    private fun flightDetails(route: PobedaRepository.RoutePrices): String? {
        val best = route.cheapest ?: return null
        val date = runCatching {
            java.time.LocalDate.parse(best.depDate)
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
        }.getOrDefault(best.depDate)
        val time = best.depTime?.let { " · выезд $it" } ?: ""
        return "✈ $date$time · ${if (best.isDirect) "прямой рейс" else "стыковочный"}"
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

        postNotification(
            "drop-$code".hashCode(), title, text, CHANNEL_ID,
            tapRequestCode = code.hashCode(),
            details = flightDetails(route),
        )
    }

    /** Общий конструктор и отправка уведомления (v2.7: переиспользуется для «цели»). */
    private fun postNotification(
        tagId: Int,
        title: String,
        text: String,
        channel: String,
        tapRequestCode: Int = 0,
        details: String? = null,
    ) {
        val tap = PendingIntent.getActivity(
            context, tapRequestCode,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_FAVORITES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // v2.13: дата и время проверки цен в тексте уведомления + детали рейса
        val checkedAt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        val body = buildString {
            append(text)
            details?.let { append("\n").append(it) }
            append("\nПроверено: $checkedAt")
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$body")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$title\n$body")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
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
