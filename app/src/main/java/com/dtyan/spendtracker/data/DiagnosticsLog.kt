package com.dtyan.spendtracker.data

import com.dtyan.spendtracker.data.db.DiagLogDao
import com.dtyan.spendtracker.data.db.DiagLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Этап работы автоучёта, к которому относится запись журнала. */
enum class LogStage(val title: String) {
    /** Уведомление пришло в слушатель. */
    NOTIFICATION("Уведомление"),

    /** Решение парсера: что распознано или почему отброшено. */
    PARSE("Разбор"),

    /** Очередь подтверждения: создание записи, дедупликация, подсказка категории. */
    QUEUE("Очередь"),

    /** Решения пользователя: подтверждение, смена категории, отклонение. */
    CONFIRM("Подтверждение"),

    /** Изменения настроек автоучёта. */
    SETTINGS("Настройки"),

    /** Жизненный цикл сервиса и своих уведомлений. */
    SERVICE("Сервис");

    companion object {
        fun fromName(raw: String?): LogStage =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SERVICE
    }
}

enum class LogLevel { INFO, WARN, ERROR;
    companion object {
        fun fromName(raw: String?): LogLevel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: INFO
    }
}

/** Запись журнала для UI и выгрузки. */
data class LogRecord(
    val id: Long,
    val at: LocalDateTime,
    val atMillis: Long,
    val stage: LogStage,
    val level: LogLevel,
    val message: String,
    val details: String?,
)

/**
 * Журнал диагностики автоучёта.
 *
 * Зачем: единственный способ понять постфактум, почему трата не появилась или почему
 * категория подобралась не так — увидеть исходный текст уведомления и всю цепочку решений.
 * Журнал живёт только на устройстве, ограничен кольцевым буфером и выгружается файлом
 * вручную — сам никуда не отправляется.
 *
 * Запись никогда не должна ломать основной сценарий, поэтому все ошибки глотаются.
 */
class DiagnosticsLog(
    private val dao: DiagLogDao,
    private val settings: SettingsStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val sinceTrim = AtomicInteger(0)

    /** Пишет событие. Если журнал выключен в настройках — тихо ничего не делает. */
    suspend fun log(
        stage: LogStage,
        message: String,
        details: String? = null,
        level: LogLevel = LogLevel.INFO,
    ) {
        if (!settings.current().diagnosticsEnabled) return
        runCatching {
            dao.insert(
                DiagLogEntity(
                    at = System.currentTimeMillis(),
                    stage = stage.name,
                    level = level.name,
                    message = message,
                    details = details?.takeIf { it.isNotBlank() },
                )
            )
            // Подрезаем не на каждой записи — незачем гонять DELETE на каждое уведомление.
            if (sinceTrim.incrementAndGet() >= TRIM_EVERY) {
                sinceTrim.set(0)
                dao.trim(MAX_ENTRIES)
            }
        }
    }

    /** Удобная обёртка: собирает блок «ключ: значение» в details. */
    suspend fun log(
        stage: LogStage,
        message: String,
        level: LogLevel = LogLevel.INFO,
        vararg fields: Pair<String, Any?>,
    ) {
        val details = fields
            .filter { it.second != null && it.second.toString().isNotBlank() }
            .joinToString("\n") { (key, value) -> "$key: $value" }
        log(stage, message, details, level)
    }

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<LogRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toRecord() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getAll(): List<LogRecord> = dao.getAll().map { it.toRecord() }

    suspend fun clear() {
        runCatching { dao.deleteAll() }
    }

    private fun DiagLogEntity.toRecord() = LogRecord(
        id = id,
        at = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone),
        atMillis = at,
        stage = LogStage.fromName(stage),
        level = LogLevel.fromName(level),
        message = message,
        details = details,
    )

    private companion object {
        /** Кольцевой буфер: столько записей держим, старые вытесняются. */
        const val MAX_ENTRIES = 3000
        const val TRIM_EVERY = 50
        const val RECENT_LIMIT = 500
    }
}
