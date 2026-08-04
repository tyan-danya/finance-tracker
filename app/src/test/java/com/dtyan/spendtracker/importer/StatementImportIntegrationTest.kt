package com.dtyan.spendtracker.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.stats.StatsCalculator
import com.dtyan.spendtracker.domain.model.Period
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * Интеграция парсер → репозиторий: реальный разбор синтетической выписки, импорт с
 * дедупликацией, повторный импорт того же файла, откат.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StatementImportIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExpenseRepository

    private val header =
        """"Дата операции";"Дата платежа";"Номер карты";"Статус";"Сумма операции";"Валюта операции";"Сумма платежа";"Валюта платежа";"Кэшбэк";"Категория";"MCC";"Описание";"Бонусы (включая кэшбэк)";"Округление на инвесткопилку";"Сумма операции с округлением""""

    private val csv = listOf(
        header,
        """"05.07.2026 12:27:59";"05.07.2026";"*1234";"OK";"-422,00";"RUB";"-422,00";"RUB";"";"Супермаркеты";"5411";"Магазин";"0,00";"28,00";"-450,00"""",
        """"11.07.2026 15:56:48";"11.07.2026";"*1234";"OK";"-499,00";"RUB";"-499,00";"RUB";"4,00";"Фастфуд";"5814";"Котофей";"4,00";"1,00";"-500,00"""",
        """"03.07.2026 12:28:29";"03.07.2026";"*1234";"OK";"57618,90";"RUB";"57618,90";"RUB";"";"Зарплата";"";"Заработная плата";"0,00";"0,00";"57618,90"""",
        """"06.07.2026 14:49:25";"06.07.2026";"*1234";"OK";"-70000,00";"RUB";"-70000,00";"RUB";"";"Переводы";"";"Между своими счетами";"0,00";"0,00";"-70000,00"""",
    ).joinToString("\r\n")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        repository = ExpenseRepository(db.categoryDao(), db.expenseDao(), db.importBatchDao())
    }

    @After
    fun tearDown() = db.close()

    /** Импортируем всё, кроме переводов между своими счетами. */
    private suspend fun importSample(): com.dtyan.spendtracker.data.ImportSummary {
        repository.seedDefaultsIfEmpty()
        val parsed = TinkoffStatementParser.parse(csv)
        val chosen = parsed.operations.filter { it.kind.includedByDefault }
        val entries = StatementImporter.toEntries(chosen)
        return repository.importEntries(entries, bank = "TBANK", fileName = "test.csv")
    }

    @Test
    fun `импорт создаёт расходы и доход, перевод себе исключён`() = runTest {
        val summary = importSample()
        // 2 покупки + 1 зарплата (перевод себе не в includedByDefault).
        assertThat(summary.imported).isEqualTo(3)

        val all = repository.getAllExpenses()
        assertThat(all).hasSize(3)
        assertThat(all.count { it.type == EntryType.EXPENSE }).isEqualTo(2)
        assertThat(all.count { it.type == EntryType.INCOME }).isEqualTo(1)

        val salary = all.first { it.type == EntryType.INCOME }
        assertThat(salary.amountMinor).isEqualTo(5761890L)
        assertThat(salary.categoryName).isEqualTo("Пополнения")
        assertThat(salary.subcategoryName).isEqualTo("Зарплата")

        val shop = all.first { it.note == "Магазин" }
        assertThat(shop.categoryName).isEqualTo("Продукты")
        assertThat(shop.subcategoryName).isEqualTo("Супермаркет")
        assertThat(shop.amountMinor).isEqualTo(42200L)
    }

    @Test
    fun `повторный импорт того же файла ничего не дублирует`() = runTest {
        importSample()
        val second = importSample()
        assertThat(second.imported).isEqualTo(0)
        assertThat(second.duplicatesInDb).isEqualTo(3)
        assertThat(repository.getAllExpenses()).hasSize(3)
    }

    @Test
    fun `дубли внутри одного файла схлопываются`() = runTest {
        repository.seedDefaultsIfEmpty()
        // Один и тот же файл, склеенный дважды (перекрывающиеся периоды).
        val doubled = (csv + "\r\n" + csv.substringAfter(header + "\r\n"))
        val parsed = TinkoffStatementParser.parse(doubled)
        val chosen = parsed.operations.filter { it.kind.includedByDefault }
        val summary = repository.importEntries(StatementImporter.toEntries(chosen), "TBANK", "d.csv")
        assertThat(summary.imported).isEqualTo(3)
        assertThat(summary.duplicatesInFile).isEqualTo(3)
    }

    @Test
    fun `откат импорта удаляет операции батча`() = runTest {
        val summary = importSample()
        val removed = repository.undoImport(summary.batchId!!)
        assertThat(removed).isEqualTo(3)
        assertThat(repository.getAllExpenses()).isEmpty()
    }

    @Test
    fun `статистика периода считает расход и доход раздельно`() = runTest {
        importSample()
        val all = repository.getAllExpenses()
        val stats = StatsCalculator.compute(all, Period.Month(YearMonth.of(2026, 7)))
        // Траты: 422 + 499 = 921 ₽; доход: 57618,90 ₽.
        assertThat(stats.totalMinor).isEqualTo(92100L)
        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.incomeMinor).isEqualTo(5761890L)
        assertThat(stats.balanceMinor).isEqualTo(5761890L - 92100L)
        // Доход не попал в разбивку по категориям трат.
        assertThat(stats.byCategory.none { it.categoryName == "Пополнения" }).isTrue()
    }

    @Test
    fun `ручная трата помечается как возможный дубль при импорте`() = runTest {
        repository.seedDefaultsIfEmpty()
        val productId = db.categoryDao().findCategoryByName("Продукты")!!.id

        // Пользователь вручную добавил ту же покупку (сумма и дата как в выписке, строка «Магазин»).
        repository.addExpense(
            com.dtyan.spendtracker.domain.model.ExpenseDraft(
                amountMinor = 42200L,
                categoryId = productId,
                date = java.time.LocalDate.of(2026, 7, 5),
                note = "кофе",
            )
        )

        val parsed = TinkoffStatementParser.parse(csv)
        val chosen = parsed.operations.filter { it.kind.includedByDefault }
        val entries = StatementImporter.toEntries(chosen)
        val verdicts = repository.checkDuplicates(entries)

        // Ровно одна операция (покупка на 422 ₽ 05.07) должна быть помечена как возможный дубль.
        val shopEntryIndex = entries.indexOfFirst { it.amountMinor == 42200L }
        assertThat(verdicts[shopEntryIndex])
            .isEqualTo(com.dtyan.spendtracker.data.DuplicateVerdict.SUSPECTED)
        // Остальные — не дубли.
        assertThat(verdicts.count { it == com.dtyan.spendtracker.data.DuplicateVerdict.SUSPECTED })
            .isEqualTo(1)
    }

    @Test
    fun `повторный импорт даёт вердикт ALREADY_IMPORTED`() = runTest {
        importSample()
        val parsed = TinkoffStatementParser.parse(csv)
        val chosen = parsed.operations.filter { it.kind.includedByDefault }
        val verdicts = repository.checkDuplicates(StatementImporter.toEntries(chosen))
        assertThat(verdicts.all { it == com.dtyan.spendtracker.data.DuplicateVerdict.ALREADY_IMPORTED })
            .isTrue()
    }

    @Test
    fun `два одинаковых платежа не оба помечаются против одной ручной траты`() = runTest {
        repository.seedDefaultsIfEmpty()
        val productId = db.categoryDao().findCategoryByName("Продукты")!!.id
        repository.addExpense(
            com.dtyan.spendtracker.domain.model.ExpenseDraft(
                amountMinor = 15000L,
                categoryId = productId,
                date = java.time.LocalDate.of(2026, 7, 10),
                note = "ручная",
            )
        )
        // Две одинаковые входящие операции на 150 ₽ 10.07.
        val e = com.dtyan.spendtracker.data.ImportEntry(
            amountMinor = 15000L,
            type = EntryType.EXPENSE,
            date = java.time.LocalDate.of(2026, 7, 10),
            note = "покупка",
            suggestedCategory = "Продукты",
            suggestedSubcategory = "Супермаркет",
            externalId = "a",
        )
        val verdicts = repository.checkDuplicates(listOf(e, e.copy(externalId = "b")))
        // Одна против ручной — дубль, вторая — уникальна.
        assertThat(verdicts.count { it == com.dtyan.spendtracker.data.DuplicateVerdict.SUSPECTED })
            .isEqualTo(1)
        assertThat(verdicts.count { it == com.dtyan.spendtracker.data.DuplicateVerdict.NONE })
            .isEqualTo(1)
    }

    @Test
    fun `батч импорта записан в журнал`() = runTest {
        importSample()
        val batches = repository.observeImportBatches().first()
        assertThat(batches).hasSize(1)
        assertThat(batches[0].bank).isEqualTo("TBANK")
        assertThat(batches[0].rowsImported).isEqualTo(3)
    }
}
