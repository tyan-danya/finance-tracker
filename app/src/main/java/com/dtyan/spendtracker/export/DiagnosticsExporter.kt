package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.data.AutoCaptureSettings
import com.dtyan.spendtracker.data.LogRecord
import com.dtyan.spendtracker.notifications.BankCatalog
import java.time.format.DateTimeFormatter

/**
 * Текстовая выгрузка журнала диагностики автоучёта.
 *
 * Файл делается так, чтобы его можно было целиком отдать разработчику: сверху — окружение
 * и настройки (без чего события не читаются), дальше — события по возрастанию времени
 * с полными подробностями.
 */
object DiagnosticsExporter {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * @param records события по возрастанию времени.
     * @param settings текущие настройки автоучёта — половина вопросов снимается ими.
     * @param environment строки об окружении: версия приложения, Android, модель, доступ к уведомлениям.
     */
    fun build(
        records: List<LogRecord>,
        settings: AutoCaptureSettings,
        environment: List<Pair<String, String>>,
    ): String = buildString {
        appendLine("# Журнал автоучёта «Траты»")
        appendLine()
        appendLine("## Окружение")
        environment.forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine("## Настройки автоучёта")
        appendLine("автоучёт: ${onOff(settings.enabled)}")
        appendLine(
            "включённые источники: " +
                settings.enabledBanks.sorted().joinToString(", ") { BankCatalog.title(it) }
                    .ifEmpty { "нет" }
        )
        appendLine("уведомлять о новых операциях: ${onOff(settings.notifyOnCapture)}")
        appendLine("напоминать о переполнении очереди: ${onOff(settings.remindQueueOverflow)}")
        appendLine("напоминать, если не заходил сутки: ${onOff(settings.remindInactivity)}")
        appendLine("журнал диагностики: ${onOff(settings.diagnosticsEnabled)}")
        appendLine()
        appendLine("## События (${records.size})")
        appendLine()

        if (records.isEmpty()) {
            appendLine("Журнал пуст: событий не было или запись выключена.")
            return@buildString
        }

        records.forEach { record ->
            appendLine("[${record.at.format(TIME)}] ${record.level.name} ${record.stage.name}: ${record.message}")
            record.details
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.forEach { line -> appendLine("    $line") }
        }
    }

    private fun onOff(value: Boolean) = if (value) "включено" else "выключено"
}
