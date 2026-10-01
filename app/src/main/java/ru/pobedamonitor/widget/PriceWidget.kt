package ru.pobedamonitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ru.pobedamonitor.MainActivity
import ru.pobedamonitor.R
import ru.pobedamonitor.data.FavoritesRepository
import java.util.Locale

/**
 * Домашний виджет «Избранное»: показывает минимальные цены избранных
 * направлений из последнего обновления приложения.
 */
class PriceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, manager, id) }
    }

    companion object {
        /** Немедленно перерисовать все экземпляры виджета. */
        fun update(context: Context) {
            val cn = ComponentName(context, PriceWidget::class.java)
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(cn)
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, PriceWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }

        private fun formatPrice(price: Int): String =
            String.format(Locale("ru"), "%,d", price).replace(',', ' ') + " ₽"

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_price)

            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, tapIntent)

            val entries = FavoritesRepository(context).loadSnapshot().take(4)

            val rowIds = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
            val nameIds = intArrayOf(R.id.name1, R.id.name2, R.id.name3, R.id.name4)
            val priceIds = intArrayOf(R.id.price1, R.id.price2, R.id.price3, R.id.price4)

            if (entries.isEmpty()) {
                views.setViewVisibility(R.id.emptyText, android.view.View.VISIBLE)
                rowIds.forEach { views.setViewVisibility(it, android.view.View.GONE) }
                views.setTextViewText(R.id.updatedAt, "")
            } else {
                views.setViewVisibility(R.id.emptyText, android.view.View.GONE)
                rowIds.forEachIndexed { i, rowId ->
                    if (i < entries.size) {
                        views.setViewVisibility(rowId, android.view.View.VISIBLE)
                        views.setTextViewText(nameIds[i], entries[i].name)
                        views.setTextViewText(
                            priceIds[i],
                            entries[i].priceRub?.let { formatPrice(it) } ?: "—",
                        )
                    } else {
                        views.setViewVisibility(rowId, android.view.View.GONE)
                    }
                }
                views.setTextViewText(R.id.updatedAt, "последние цены из приложения")
            }

            manager.updateAppWidget(id, views)
        }
    }
}
