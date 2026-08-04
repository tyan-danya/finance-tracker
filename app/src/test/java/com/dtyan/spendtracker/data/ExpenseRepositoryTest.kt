package com.dtyan.spendtracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.data.db.CategoryDao
import com.dtyan.spendtracker.data.db.ExpenseDao
import com.dtyan.spendtracker.data.db.ExpenseEntity
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
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

/**
 * Тесты репозитория поверх настоящей Room-базы (in-memory, Robolectric).
 * Все даты фиксированные — никакого LocalDate.now(), чтобы тесты не «протухали».
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExpenseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var repository: ExpenseRepository

    private val green = 0xFF4CAF50.toInt()
    private val red = 0xFFEF5350.toInt()

    // Фиксированные даты для всех тестов.
    private val leapDay: LocalDate = LocalDate.of(2024, 2, 29)
    private val dayJan: LocalDate = LocalDate.of(2024, 1, 10)
    private val dayMar: LocalDate = LocalDate.of(2024, 3, 5)
    private val dayDec: LocalDate = LocalDate.of(2023, 12, 31)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // В in-memory БД внешние ключи нужно включать явно, иначе RESTRICT/SET NULL/CASCADE не сработают.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        categoryDao = db.categoryDao()
        expenseDao = db.expenseDao()
        repository = ExpenseRepository(categoryDao, expenseDao)
    }

    @After
    fun tearDown() = db.close()

    // --- seedDefaultsIfEmpty -------------------------------------------------

    @Test
    fun `seedDefaultsIfEmpty создаёт все дефолтные категории и подкатегории`() = runTest {
        repository.seedDefaultsIfEmpty()

        val tree = repository.observeCategoryTree(includeArchived = true).first()
        val expectedNames = DefaultCategories.tree.map { it.name }

        assertThat(tree).hasSize(DefaultCategories.tree.size)
        assertThat(tree.map { it.category.name }).containsExactlyElementsIn(expectedNames)

        val expectedSubCount = DefaultCategories.tree.sumOf { it.subcategories.size }
        val actualSubCount = tree.sumOf { it.subcategories.size }
        assertThat(actualSubCount).isEqualTo(expectedSubCount)

        // Все дефолтные категории помечены как встроенные.
        assertThat(tree.all { it.category.isBuiltIn }).isTrue()
        assertThat(tree.all { branch -> branch.subcategories.all { it.isBuiltIn } }).isTrue()
    }

    @Test
    fun `повторный seedDefaultsIfEmpty ничего не дублирует`() = runTest {
        repository.seedDefaultsIfEmpty()
        val before = repository.observeCategoryTree(includeArchived = true).first()
        val categoriesBefore = before.size
        val subcategoriesBefore = before.sumOf { it.subcategories.size }

        repository.seedDefaultsIfEmpty()

        val after = repository.observeCategoryTree(includeArchived = true).first()
        assertThat(after).hasSize(categoriesBefore)
        assertThat(after.sumOf { it.subcategories.size }).isEqualTo(subcategoriesBefore)
    }

    // --- observeCategoryTree -------------------------------------------------

    @Test
    fun `observeCategoryTree отдаёт дерево в порядке sortOrder с правильной вложенностью`() = runTest {
        repository.seedDefaultsIfEmpty()

        val tree = repository.observeCategoryTree().first()

        // Порядок категорий совпадает с порядком в DefaultCategories.tree (sortOrder = индекс).
        assertThat(tree.map { it.category.name })
            .containsExactlyElementsIn(DefaultCategories.tree.map { it.name })
            .inOrder()

        // Подкатегории лежат внутри своей категории и в порядке seed'а.
        DefaultCategories.tree.forEachIndexed { index, seed ->
            val branch = tree[index]
            assertThat(branch.subcategories.map { it.name })
                .containsExactlyElementsIn(seed.subcategories)
                .inOrder()
            assertThat(branch.subcategories.all { it.categoryId == branch.category.id }).isTrue()
        }
    }

    @Test
    fun `новая пользовательская категория встаёт в конец дерева`() = runTest {
        repository.seedDefaultsIfEmpty()
        repository.addCategory("Ремонт велосипеда", "🔧", green)

        val tree = repository.observeCategoryTree().first()

        // sortOrder = Int.MAX_VALUE, поэтому пользовательская категория последняя.
        assertThat(tree.last().category.name).isEqualTo("Ремонт велосипеда")
        assertThat(tree.last().category.isBuiltIn).isFalse()
    }

    @Test
    fun `observeCategoryTree по умолчанию скрывает архивные категории`() = runTest {
        val kept = repository.addCategory("Продукты", "🛒", green)
        val archived = repository.addCategory("Прочее", "📦", red)
        repository.setCategoryArchived(archived, true)

        val visible = repository.observeCategoryTree().first()
        assertThat(visible.map { it.category.id }).containsExactly(kept)

        val all = repository.observeCategoryTree(includeArchived = true).first()
        assertThat(all.map { it.category.id }).containsExactly(kept, archived)
    }

    @Test
    fun `observeCategoryTree по умолчанию скрывает архивные подкатегории`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)
        val visibleSub = repository.addSubcategory(categoryId, "Кофе")
        val archivedSub = repository.addSubcategory(categoryId, "Бар")
        repository.setSubcategoryArchived(archivedSub, true)

        val visible = repository.observeCategoryTree().first().single()
        assertThat(visible.subcategories.map { it.id }).containsExactly(visibleSub)

        val all = repository.observeCategoryTree(includeArchived = true).first().single()
        assertThat(all.subcategories.map { it.id }).containsExactly(visibleSub, archivedSub)
    }

    @Test
    fun `setCategoryArchived скрывает и возвращает категорию в дерево`() = runTest {
        val id = repository.addCategory("Спорт", "🏃", green)

        repository.setCategoryArchived(id, true)
        assertThat(repository.observeCategoryTree().first()).isEmpty()

        repository.setCategoryArchived(id, false)
        val restored = repository.observeCategoryTree().first().single()
        assertThat(restored.category.id).isEqualTo(id)
        assertThat(restored.category.archived).isFalse()
    }

    @Test
    fun `setSubcategoryArchived скрывает и возвращает подкатегорию в дерево`() = runTest {
        val categoryId = repository.addCategory("Спорт", "🏃", green)
        val subId = repository.addSubcategory(categoryId, "Абонемент")

        repository.setSubcategoryArchived(subId, true)
        assertThat(repository.observeCategoryTree().first().single().subcategories).isEmpty()

        repository.setSubcategoryArchived(subId, false)
        val restored = repository.observeCategoryTree().first().single().subcategories.single()
        assertThat(restored.id).isEqualTo(subId)
        assertThat(restored.archived).isFalse()
    }

    // --- addExpense ----------------------------------------------------------

    @Test
    fun `addExpense возвращает положительный id и трата видна в observeExpenses`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)

        val id = repository.addExpense(
            ExpenseDraft(amountMinor = 123_45, categoryId = categoryId, date = dayJan)
        )

        assertThat(id).isGreaterThan(0L)
        val observed = repository.observeExpenses().first()
        assertThat(observed.map { it.id }).containsExactly(id)
        assertThat(repository.getAllExpenses().map { it.id }).containsExactly(id)
    }

    @Test
    fun `addExpense сохраняет все поля без искажений и подставляет имена из джойна`() = runTest {
        val categoryId = repository.addCategory("Кафе и рестораны", "🍽️", red)
        val subId = repository.addSubcategory(categoryId, "Кофе")

        val id = repository.addExpense(
            ExpenseDraft(
                amountMinor = 987_654_321L,
                categoryId = categoryId,
                subcategoryId = subId,
                date = dayMar,
                note = "Латте на вынос, двойной",
                paymentMethod = PaymentMethod.CASH,
                currency = "RUB",
            )
        )

        val record = repository.getAllExpenses().single()
        assertThat(record.id).isEqualTo(id)
        assertThat(record.amountMinor).isEqualTo(987_654_321L)
        assertThat(record.currency).isEqualTo("RUB")
        assertThat(record.date).isEqualTo(dayMar)
        assertThat(record.note).isEqualTo("Латте на вынос, двойной")
        assertThat(record.paymentMethod).isEqualTo(PaymentMethod.CASH)
        // Имена приезжают из JOIN/LEFT JOIN, а не из черновика.
        assertThat(record.categoryName).isEqualTo("Кафе и рестораны")
        assertThat(record.subcategoryId).isEqualTo(subId)
        assertThat(record.subcategoryName).isEqualTo("Кофе")
        assertThat(record.createdAt).isGreaterThan(0L)
    }

    @Test
    fun `високосная дата 2024-02-29 сохраняется и читается ровно такой же`() = runTest {
        val categoryId = repository.addCategory("Прочее", "📦", green)

        repository.addExpense(
            ExpenseDraft(amountMinor = 100, categoryId = categoryId, date = leapDay)
        )

        val record = repository.getAllExpenses().single()
        // epochDay -> LocalDate обязан вернуть ровно 29 февраля 2024 (високосный год).
        assertThat(record.date).isEqualTo(LocalDate.of(2024, 2, 29))
        assertThat(record.date.toEpochDay()).isEqualTo(leapDay.toEpochDay())
        assertThat(record.date.dayOfMonth).isEqualTo(29)
        assertThat(record.date.monthValue).isEqualTo(2)
        assertThat(record.date.year).isEqualTo(2024)
    }

    @Test
    fun `трата без подкатегории отдаёт null в subcategoryId и subcategoryName`() = runTest {
        val categoryId = repository.addCategory("Транспорт", "🚇", green)
        repository.addSubcategory(categoryId, "Такси") // подкатегория есть, но не выбрана

        repository.addExpense(
            ExpenseDraft(amountMinor = 5_000, categoryId = categoryId, subcategoryId = null, date = dayJan)
        )

        val record = repository.getAllExpenses().single()
        assertThat(record.subcategoryId).isNull()
        assertThat(record.subcategoryName).isNull()
        assertThat(record.categoryName).isEqualTo("Транспорт")
    }

    @Test
    fun `observeExpenses сортирует траты по дате по убыванию`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        repository.addExpense(ExpenseDraft(amountMinor = 1, categoryId = categoryId, date = dayJan))
        repository.addExpense(ExpenseDraft(amountMinor = 2, categoryId = categoryId, date = dayDec))
        repository.addExpense(ExpenseDraft(amountMinor = 3, categoryId = categoryId, date = dayMar))

        val dates = repository.observeExpenses().first().map { it.date }
        assertThat(dates).containsExactly(dayMar, dayJan, dayDec).inOrder()
    }

    // --- updateExpense / delete ---------------------------------------------

    @Test
    fun `updateExpense меняет поля и не создаёт новую запись`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        val otherCategoryId = repository.addCategory("Здоровье", "💊", red)
        val subId = repository.addSubcategory(otherCategoryId, "Аптека")
        val id = repository.addExpense(
            ExpenseDraft(
                amountMinor = 1_000,
                categoryId = categoryId,
                date = dayJan,
                note = "старое",
                paymentMethod = PaymentMethod.CARD,
            )
        )

        repository.updateExpense(
            ExpenseDraft(
                id = id,
                amountMinor = 25_000L,
                categoryId = otherCategoryId,
                subcategoryId = subId,
                date = dayMar,
                note = "новое",
                paymentMethod = PaymentMethod.ONLINE,
            )
        )

        val records = repository.getAllExpenses()
        assertThat(records).hasSize(1) // count не вырос
        val updated: ExpenseRecord = records.single()
        assertThat(updated.id).isEqualTo(id)
        assertThat(updated.amountMinor).isEqualTo(25_000L)
        assertThat(updated.categoryId).isEqualTo(otherCategoryId)
        assertThat(updated.categoryName).isEqualTo("Здоровье")
        assertThat(updated.subcategoryName).isEqualTo("Аптека")
        assertThat(updated.date).isEqualTo(dayMar)
        assertThat(updated.note).isEqualTo("новое")
        assertThat(updated.paymentMethod).isEqualTo(PaymentMethod.ONLINE)
    }

    @Test
    fun `updateExpense сохраняет исходный createdAt`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        // Вставляем напрямую через DAO с заведомо фиксированным createdAt,
        // иначе System.currentTimeMillis() внутри маппера сделал бы проверку недетерминированной.
        val originalCreatedAt = 1_700_000_000_000L
        val id = expenseDao.insert(
            ExpenseEntity(
                amountMinor = 1_000,
                categoryId = categoryId,
                subcategoryId = null,
                epochDay = dayJan.toEpochDay(),
                note = "исходная",
                paymentMethod = PaymentMethod.CARD.name,
                createdAt = originalCreatedAt,
            )
        )

        repository.updateExpense(
            ExpenseDraft(
                id = id,
                amountMinor = 2_000,
                categoryId = categoryId,
                date = dayMar,
                note = "обновлённая",
            )
        )

        val record = repository.getAllExpenses().single()
        assertThat(record.createdAt).isEqualTo(originalCreatedAt)
        assertThat(record.amountMinor).isEqualTo(2_000L)
    }

    @Test
    fun `updateExpense с несуществующим id не падает и ничего не меняет`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        val id = repository.addExpense(
            ExpenseDraft(amountMinor = 1_000, categoryId = categoryId, date = dayJan, note = "живая")
        )

        repository.updateExpense(
            ExpenseDraft(
                id = id + 9_999,
                amountMinor = 42,
                categoryId = categoryId,
                date = dayMar,
                note = "призрак",
            )
        )

        val records = repository.getAllExpenses()
        assertThat(records).hasSize(1)
        assertThat(records.single().id).isEqualTo(id)
        assertThat(records.single().amountMinor).isEqualTo(1_000L)
        assertThat(records.single().note).isEqualTo("живая")
    }

    @Test
    fun `deleteExpense удаляет только указанную трату`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        val first = repository.addExpense(ExpenseDraft(amountMinor = 1, categoryId = categoryId, date = dayJan))
        val second = repository.addExpense(ExpenseDraft(amountMinor = 2, categoryId = categoryId, date = dayMar))

        repository.deleteExpense(first)

        assertThat(repository.getAllExpenses().map { it.id }).containsExactly(second)
    }

    @Test
    fun `deleteAllExpenses очищает все траты, но не категории`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        repository.addExpense(ExpenseDraft(amountMinor = 1, categoryId = categoryId, date = dayJan))
        repository.addExpense(ExpenseDraft(amountMinor = 2, categoryId = categoryId, date = dayMar))

        repository.deleteAllExpenses()

        assertThat(repository.getAllExpenses()).isEmpty()
        assertThat(repository.observeExpenses().first()).isEmpty()
        assertThat(repository.observeCategoryTree().first()).hasSize(1)
    }

    // --- категории -----------------------------------------------------------

    @Test
    fun `addCategory создаёт категорию с переданными иконкой и цветом`() = runTest {
        val id = repository.addCategory("Кофейни", "☕", red)

        assertThat(id).isGreaterThan(0L)
        val category = repository.observeCategoryTree().first().single().category
        assertThat(category.id).isEqualTo(id)
        assertThat(category.name).isEqualTo("Кофейни")
        assertThat(category.icon).isEqualTo("☕")
        assertThat(category.colorArgb).isEqualTo(red)
        assertThat(category.isBuiltIn).isFalse()
    }

    @Test
    fun `addCategory с существующим именем возвращает тот же id и не создаёт дубликат`() = runTest {
        val first = repository.addCategory("Кофейни", "☕", red)
        val second = repository.addCategory("Кофейни", "🍽️", green)

        assertThat(second).isEqualTo(first)
        assertThat(repository.observeCategoryTree(includeArchived = true).first()).hasSize(1)
        // Повторный вызов не перезаписывает иконку/цвет.
        assertThat(repository.observeCategoryTree().first().single().category.icon).isEqualTo("☕")
    }

    @Test
    fun `addCategory обрезает пробелы вокруг имени`() = runTest {
        val id = repository.addCategory("  Кофе  ", "☕", red)

        val category = repository.observeCategoryTree().first().single().category
        assertThat(category.name).isEqualTo("Кофе")
        // Имя с пробелами считается тем же самым.
        assertThat(repository.addCategory("Кофе", "☕", red)).isEqualTo(id)
    }

    @Test
    fun `addCategory с пустым именем бросает IllegalArgumentException`() = runTest {
        val blank = catching { repository.addCategory("   ", "☕", red) }
        assertThat(blank).isInstanceOf(IllegalArgumentException::class.java)

        val empty = catching { repository.addCategory("", "☕", red) }
        assertThat(empty).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(repository.observeCategoryTree(includeArchived = true).first()).isEmpty()
    }

    @Test
    fun `renameCategory меняет имя, иконку и цвет`() = runTest {
        val id = repository.addCategory("Кофейни", "☕", red)

        repository.renameCategory(id, "Кафе и рестораны", "🍽️", green)

        val category = repository.observeCategoryTree().first().single().category
        assertThat(category.id).isEqualTo(id)
        assertThat(category.name).isEqualTo("Кафе и рестораны")
        assertThat(category.icon).isEqualTo("🍽️")
        assertThat(category.colorArgb).isEqualTo(green)
    }

    @Test
    fun `renameCategory с несуществующим id ничего не ломает`() = runTest {
        val id = repository.addCategory("Кофейни", "☕", red)

        repository.renameCategory(id + 500, "Ерунда", "📦", green)

        assertThat(repository.observeCategoryTree().first().single().category.name).isEqualTo("Кофейни")
    }

    // --- подкатегории --------------------------------------------------------

    @Test
    fun `addSubcategory создаёт подкатегорию и не дублирует её в той же категории`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)

        val first = repository.addSubcategory(categoryId, "Кофе")
        val second = repository.addSubcategory(categoryId, "Кофе")

        assertThat(first).isGreaterThan(0L)
        assertThat(second).isEqualTo(first)
        assertThat(repository.observeCategoryTree().first().single().subcategories).hasSize(1)
    }

    @Test
    fun `addSubcategory обрезает пробелы вокруг имени`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)

        val id = repository.addSubcategory(categoryId, " Кофе ")

        val sub = repository.observeCategoryTree().first().single().subcategories.single()
        assertThat(sub.id).isEqualTo(id)
        assertThat(sub.name).isEqualTo("Кофе")
        assertThat(repository.addSubcategory(categoryId, "Кофе")).isEqualTo(id)
    }

    @Test
    fun `addSubcategory с пустым именем бросает IllegalArgumentException`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)

        val thrown = catching { repository.addSubcategory(categoryId, "  ") }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(repository.observeCategoryTree().first().single().subcategories).isEmpty()
    }

    @Test
    fun `одинаковые имена подкатегорий в разных категориях разрешены`() = runTest {
        val cafe = repository.addCategory("Кафе", "🍽️", green)
        val products = repository.addCategory("Продукты", "🛒", red)

        val cafeCoffee = repository.addSubcategory(cafe, "Кофе")
        val productsCoffee = repository.addSubcategory(products, "Кофе")

        assertThat(productsCoffee).isNotEqualTo(cafeCoffee)
        val tree = repository.observeCategoryTree().first()
        assertThat(tree.first { it.category.id == cafe }.subcategories.map { it.id })
            .containsExactly(cafeCoffee)
        assertThat(tree.first { it.category.id == products }.subcategories.map { it.id })
            .containsExactly(productsCoffee)
    }

    // --- удаление категорий/подкатегорий ------------------------------------

    @Test
    fun `deleteCategoryIfUnused возвращает false и не удаляет категорию с тратами`() = runTest {
        val categoryId = repository.addCategory("Продукты", "🛒", green)
        repository.addExpense(ExpenseDraft(amountMinor = 1_000, categoryId = categoryId, date = dayJan))

        val deleted = repository.deleteCategoryIfUnused(categoryId)

        assertThat(deleted).isFalse()
        assertThat(repository.observeCategoryTree(includeArchived = true).first()).hasSize(1)
        assertThat(repository.getAllExpenses()).hasSize(1)
    }

    @Test
    fun `deleteCategoryIfUnused возвращает true и удаляет категорию без трат`() = runTest {
        val used = repository.addCategory("Продукты", "🛒", green)
        val unused = repository.addCategory("Прочее", "📦", red)
        repository.addExpense(ExpenseDraft(amountMinor = 1_000, categoryId = used, date = dayJan))

        val deleted = repository.deleteCategoryIfUnused(unused)

        assertThat(deleted).isTrue()
        assertThat(repository.observeCategoryTree(includeArchived = true).first().map { it.category.id })
            .containsExactly(used)
    }

    @Test
    fun `удаление неиспользуемой категории каскадом удаляет её подкатегории`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)
        repository.addSubcategory(categoryId, "Кофе")
        repository.addSubcategory(categoryId, "Обед")
        assertThat(categoryDao.getSubcategories(categoryId)).hasSize(2)

        assertThat(repository.deleteCategoryIfUnused(categoryId)).isTrue()

        // ForeignKey.CASCADE: подкатегории уходят вместе с категорией.
        assertThat(categoryDao.getSubcategories(categoryId)).isEmpty()
        assertThat(categoryDao.observeAllSubcategories().first()).isEmpty()
    }

    @Test
    fun `deleteSubcategoryIfUnused возвращает false и не удаляет подкатегорию с тратами`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)
        val subId = repository.addSubcategory(categoryId, "Кофе")
        repository.addExpense(
            ExpenseDraft(amountMinor = 30_000, categoryId = categoryId, subcategoryId = subId, date = dayJan)
        )

        val deleted = repository.deleteSubcategoryIfUnused(subId)

        assertThat(deleted).isFalse()
        assertThat(categoryDao.getSubcategories(categoryId).map { it.id }).containsExactly(subId)
        assertThat(repository.getAllExpenses().single().subcategoryId).isEqualTo(subId)
    }

    @Test
    fun `deleteSubcategoryIfUnused возвращает true и удаляет подкатегорию без трат`() = runTest {
        val categoryId = repository.addCategory("Кафе", "🍽️", green)
        val used = repository.addSubcategory(categoryId, "Кофе")
        val unused = repository.addSubcategory(categoryId, "Бар")
        repository.addExpense(
            ExpenseDraft(amountMinor = 30_000, categoryId = categoryId, subcategoryId = used, date = dayJan)
        )

        val deleted = repository.deleteSubcategoryIfUnused(unused)

        assertThat(deleted).isTrue()
        assertThat(categoryDao.getSubcategories(categoryId).map { it.id }).containsExactly(used)
    }

    // --- утилиты -------------------------------------------------------------

    /** Ловит исключение из suspend-блока: JUnit-овский assertThrows не умеет в suspend-лямбды. */
    private suspend fun catching(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (t: Throwable) {
            t
        }
}
