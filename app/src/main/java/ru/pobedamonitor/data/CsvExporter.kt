package ru.pobedamonitor.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/**
 * Экспорт всей накопленной истории цен в CSV-файл (v2.5).
 *
 * Файл пишется в cacheDir/export и отдаётся через FileProvider — так его можно
 * сохранить через системный «Поделиться» или открыть в любом файловом менеджере,
 * не требуя разрешений на хранилище.
 */
class CsvExporter(private val context: Context) {

    /**
     * Формирует CSV из [history] (directionKey -> (дата ISO -> цена)).
     * Колонки: direction;from;to;date;price_rub. Разделитель — ';' (совместимо
     * с русскими локалями Excel/LibreOffice), кодировка UTF-8 с BOM.
     */
    fun export(history: Map<String, Map<String, Int>>): File? {
        if (history.isEmpty()) return null
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "pobeda_price_history_${LocalDate.now()}.csv")
        file.outputStream().use { out ->
            // BOM, чтобы Excel распознал UTF-8
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            val sb = StringBuilder()
            sb.append("направление;откуда;куда;дата;цена_rub\n")
            history.toSortedMap().forEach { (code, days) ->
                val parts = code.split("-", limit = 2)
                val from = parts.getOrElse(0) { "" }
                val to = parts.getOrElse(1) { "" }
                days.toSortedMap().forEach { (dateIso, price) ->
                    sb.append("$code;$from;$to;$dateIso;$price\n")
                }
            }
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        return file
    }

    /** Uri для отправки файла через FileProvider (authority: <applicationId>.fileprovider). */
    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
