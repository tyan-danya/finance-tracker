package com.dtyan.spendtracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.PendingStatus
import com.dtyan.spendtracker.domain.model.SuggestionSource
import com.dtyan.spendtracker.notifications.NotificationParser
import com.dtyan.spendtracker.notifications.toEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Очередь операций из уведомлений: приём, дедупликация, подтверждение и отклонение.
 * Главная проверка — черновики не видны ни списку трат, ни статистике, пока их не подтвердили.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PendingOperationsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExpenseRepository

    private val zone: ZoneId = ZoneOffset.UTC
    private val day: LocalDate = LocalDate.of(2026, 8, 18)
    private val postedAt: Long = day.atTime(12, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val TBANK = "com.idamob.tinkoff.android"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        repository = ExpenseRepository(
            db.categoryDao(),
            db.expenseDao(),
            db.importBatchDao(),
            db.pendingOperationDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    /** Кладёт уведомление в очередь так же, как это делает сервис. */
    private suspend fun ingest(text: String, at: Long = postedAt, title: String = "Покупка"): Long? {
        val parsed = NotificationParser.parse(TBANK, title, text, at) ?: return null
        return repository.addPendingOperation(parsed.toEntry(zone))
    }

    private val purchase = "Покупка, карта *1234. 1 500 ₽. Пятёрочка. Доступно 12 345 ₽"

    @Test
    fun `распознанное уведомление попадает в очередь, но не в траты`() = runTest {
        repository.seedDefaultsIfEmpty()

        val id = ingest(purchase)

        assertThat(id).isNotNull()
        val pending = repository.observePendingOperations().first()
        assertThat(pending).hasSize(1)
        assertThat(pending.first().amountMinor).isEqualTo(150_000)
        assertThat(pending.first().merchant).isEqualTo("Пятёрочка")
        assertThat(pending.first().status).isEqualTo(PendingStatus.PENDING)

        // Самое важное: в тратах пусто, пока пользователь не подтвердил.
        assertThat(repository.getAllExpenses()).isEmpty()
    }

    @Test
    fun `категория подставляется по словарю мерчантов`() = runTest {
        repository.seedDefaultsIfEmpty()

        ingest(purchase)

        val operation = repository.observePendingOperations().first().single()
        assertThat(operation.categoryName).isEqualTo("Продукты")
        assertThat(operation.subcategoryName).isEqualTo("Супермаркет")
        assertThat(operation.suggestionSource).isEqualTo(SuggestionSource.DICTIONARY)
        assertThat(operation.isReadyToConfirm).isTrue()
    }

    @Test
    fun `категория берётся из истории подтверждений, если мерчант уже встречался`() = runTest {
        repository.seedDefaultsIfEmpty()
        val cafeId = repository.observeCategoryTree().first()
            .first { it.category.name == "Кафе и рестораны" }.category.id

        // Пользователь однажды отнёс этого мерчанта к «Кафе» — запоминаем через подтверждение.
        val firstId = ingest("Покупка, карта *1234. 300 ₽. КОФЕЙНЯ У ДОМА")!!
        val first = repository.getPendingOperation(firstId)!!
        repository.confirmPendingOperation(
            id = firstId,
            draft = ExpenseDraft(
                amountMinor = first.amountMinor,
                categoryId = cafeId,
                date = first.date,
                note = first.merchant.orEmpty(),
                type = EntryType.EXPENSE,
            ),
        )

        val secondId = ingest("Покупка, карта *1234. 250 ₽. КОФЕЙНЯ У ДОМА", postedAt + 3_600_000)!!
        val second = repository.getPendingOperation(secondId)!!

        assertThat(second.categoryId).isEqualTo(cafeId)
        assertThat(second.suggestionSource).isEqualTo(SuggestionSource.HISTORY)
    }

    @Test
    fun `повтор того же уведомления не создаёт вторую запись`() = runTest {
        repository.seedDefaultsIfEmpty()

        val first = ingest(purchase)
        val repeat = ingest(purchase, postedAt + 30_000)

        assertThat(first).isNotNull()
        assertThat(repeat).isNull()
        assertThat(repository.observePendingOperations().first()).hasSize(1)
    }

    @Test
    fun `уже подтверждённая операция повторно в очередь не возвращается`() = runTest {
        repository.seedDefaultsIfEmpty()
        val id = ingest(purchase)!!
        val operation = repository.getPendingOperation(id)!!
        repository.confirmPendingOperation(
            id = id,
            draft = ExpenseDraft(
                amountMinor = operation.amountMinor,
                categoryId = operation.categoryId!!,
                subcategoryId = operation.subcategoryId,
                date = operation.date,
                type = EntryType.EXPENSE,
            ),
        )

        // Тот же пуш прилетел снова (например, после перезагрузки телефона).
        val again = ingest(purchase, postedAt + 30_000)

        assertThat(again).isNull()
        assertThat(repository.observePendingOperations().first()).isEmpty()
        assertThat(repository.getAllExpenses()).hasSize(1)
    }

    @Test
    fun `подтверждение создаёт трату с правками пользователя и убирает её из очереди`() = runTest {
        repository.seedDefaultsIfEmpty()
        val id = ingest(purchase)!!
        val transport = repository.observeCategoryTree().first()
            .first { it.category.name == "Транспорт" }.category.id

        val result = repository.confirmPendingOperation(
            id = id,
            draft = ExpenseDraft(
                amountMinor = 99_900,
                categoryId = transport,
                date = day.minusDays(1),
                note = "поправил вручную",
                paymentMethod = PaymentMethod.CASH,
                type = EntryType.EXPENSE,
            ),
        )

        assertThat(result).isInstanceOf(ConfirmResult.Confirmed::class.java)
        assertThat(repository.observePendingOperations().first()).isEmpty()

        val expense = repository.getAllExpenses().single()
        assertThat(expense.amountMinor).isEqualTo(99_900)
        assertThat(expense.categoryName).isEqualTo("Транспорт")
        assertThat(expense.date).isEqualTo(day.minusDays(1))
        assertThat(expense.note).isEqualTo("поправил вручную")
        assertThat(expense.paymentMethod).isEqualTo(PaymentMethod.CASH)
    }

    @Test
    fun `отклонение удаляет операцию и позволяет вернуть её обратно`() = runTest {
        repository.seedDefaultsIfEmpty()
        val id = ingest(purchase)!!

        val removed = repository.rejectPendingOperation(id)

        assertThat(removed).isNotNull()
        assertThat(repository.observePendingOperations().first()).isEmpty()
        assertThat(repository.getAllExpenses()).isEmpty()

        // «Вернуть» из снекбара.
        repository.addPendingOperation(removed!!)
        assertThat(repository.observePendingOperations().first()).hasSize(1)
    }

    @Test
    fun `операция с непонятным типом попадает в очередь как не распознанная`() = runTest {
        repository.seedDefaultsIfEmpty()

        val id = ingest("Абонентская плата 199 ₽ за тариф", title = "Т-Банк")!!
        val operation = repository.getPendingOperation(id)!!

        assertThat(operation.status).isEqualTo(PendingStatus.UNPARSED)
        assertThat(operation.isReadyToConfirm).isFalse()
        assertThat(operation.rawText).contains("Абонентская плата")
    }

    @Test
    fun `похожая на существующую трату операция помечается возможным дубликатом`() = runTest {
        repository.seedDefaultsIfEmpty()
        val categoryId = repository.observeCategoryTree().first().first().category.id
        // Ту же покупку пользователь уже завёл руками.
        repository.addExpense(
            ExpenseDraft(amountMinor = 150_000, categoryId = categoryId, date = day)
        )

        ingest(purchase)
        val operations = repository.observePendingOperations().first()

        assertThat(repository.findSuspectedDuplicates(operations)).containsExactly(operations.first().id)
    }

    @Test
    fun `отклонить все очищает очередь целиком`() = runTest {
        repository.seedDefaultsIfEmpty()
        ingest(purchase)
        ingest("Покупка, карта *1234. 320 ₽. МАГНИТ", postedAt + 600_000)

        val removed = repository.rejectAllPendingOperations()

        assertThat(removed).isEqualTo(2)
        assertThat(repository.observePendingOperations().first()).isEmpty()
    }
}
