package com.dtyan.spendtracker.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class ExportFormat(val ext: String, val mime: String, val title: String) {
    CSV("csv", "text/csv", "CSV — таблица"),
    JSON("json", "application/json", "JSON — полные данные"),
    ANALYSIS("md", "text/markdown", "Отчёт для ИИ-анализа"),
}

/**
 * Запись экспортных файлов в кэш и шаринг их наружу через FileProvider.
 *
 * Authority провайдера объявлен в манифесте как `${applicationId}.fileprovider`,
 * а у debug-сборки applicationId имеет суффикс `.debug` — поэтому пакет берём
 * из [Context.getPackageName], а не хардкодим.
 */
class ExportManager(private val context: Context) {

    fun write(format: ExportFormat, content: String, today: LocalDate): Uri {
        val dir = File(context.cacheDir, EXPORTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        cleanupOld(dir)

        val file = File(dir, "traty_${today.format(FILE_DATE_FORMAT)}.${format.ext}")
        // Для CSV дописываем BOM, иначе Excel открывает кириллицу как кракозябры.
        val payload = if (format == ExportFormat.CSV) UTF8_BOM + content else content
        file.writeText(payload, Charsets.UTF_8)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri, format: ExportFormat): Intent {
        val subject = "Траты — ${format.title}"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, subject).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Подчищаем прошлые выгрузки старше суток, чтобы кэш не рос бесконечно. */
    private fun cleanupOld(dir: File) {
        val threshold = System.currentTimeMillis() - DAY_MILLIS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < threshold) file.delete()
            }
        }
    }

    private companion object {
        const val EXPORTS_DIR = "exports"
        const val UTF8_BOM = "\uFEFF"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        val FILE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
