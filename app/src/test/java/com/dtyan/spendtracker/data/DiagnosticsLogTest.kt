package com.dtyan.spendtracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.export.DiagnosticsExporter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Журнал диагностики: запись, выключение, кольцевой буфер и выгрузка файлом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DiagnosticsLogTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var log: DiagnosticsLog

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Настройки шарятся между тестами через SharedPreferences — сбрасываем явно.
        settings = SettingsStore(context)
        settings.setDiagnosticsEnabled(true)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        log = DiagnosticsLog(db.diagLogDao(), settings)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `событие пишется вместе с подробностями`() = runTest {
        log.log(
            stage = LogStage.NOTIFICATION,
            message = "Пришло уведомление: Т-Банк",
            details = "текст: Покупка 500 ₽",
        )

        val records = log.observeRecent().first()
        assertThat(records).hasSize(1)
        assertThat(records.first().stage).isEqualTo(LogStage.NOTIFICATION)
        assertThat(records.first().level).isEqualTo(LogLevel.INFO)
        assertThat(records.first().details).contains("Покупка 500")
    }

    @Test
    fun `при выключенном журнале ничего не пишется`() = runTest {
        settings.setDiagnosticsEnabled(false)

        log.log(LogStage.PARSE, "не должно попасть в журнал")

        assertThat(log.observeRecent().first()).isEmpty()
    }

    @Test
    fun `очистка удаляет все записи`() = runTest {
        repeat(5) { log.log(LogStage.QUEUE, "событие $it") }

        log.clear()

        assertThat(log.observeRecent().first()).isEmpty()
    }

    @Test
    fun `журнал не растёт бесконечно`() = runTest {
        // Буфер — 3000 записей, подрезка идёт пачками по 50.
        repeat(3_100) { log.log(LogStage.QUEUE, "событие $it") }

        val total = log.getAll().size
        assertThat(total).isAtMost(3_050)
        // Свежие записи на месте.
        assertThat(log.getAll().last().message).isEqualTo("событие 3099")
    }

    @Test
    fun `выгрузка содержит окружение, настройки и события`() = runTest {
        log.log(LogStage.PARSE, "Разобрано: Покупка 500,00 ₽", details = "мерчант: Пятёрочка")

        val text = DiagnosticsExporter.build(
            records = log.getAll(),
            settings = settings.current(),
            environment = listOf("версия приложения" to "1.2 (3)"),
        )

        assertThat(text).contains("версия приложения: 1.2 (3)")
        assertThat(text).contains("## Настройки автоучёта")
        assertThat(text).contains("Разобрано: Покупка 500,00 ₽")
        assertThat(text).contains("мерчант: Пятёрочка")
    }
}
