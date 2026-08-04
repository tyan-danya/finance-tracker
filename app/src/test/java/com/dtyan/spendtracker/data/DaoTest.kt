package com.dtyan.spendtracker.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.data.db.CategoryDao
import com.dtyan.spendtracker.data.db.CategoryEntity
import com.dtyan.spendtracker.data.db.ExpenseDao
import com.dtyan.spendtracker.data.db.ExpenseEntity
import com.dtyan.spendtracker.data.db.SubcategoryEntity
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
 * Тесты уровня DAO: джойны, внешние ключи (RESTRICT / SET NULL / CASCADE),
 * стратегия IGNORE при конфликте уникальных индексов и счётчики.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DaoTest {

    private lateinit var db: AppDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var expenseDao: ExpenseDao

    private val color = 0xFF4CAF50.toInt()

    // Фиксированные даты — никаких LocalDate.now().
    private val day1: LocalDate = LocalDate.of(2024, 1, 10)
    private val day2: LocalDate = LocalDate.of(2024, 2, 29) // високосный день
    private val day3: LocalDate = LocalDate.of(2024, 3, 5)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Без этого PRAGMA внешние ключи в in-memory БД не проверяются.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        categoryDao = db.categoryDao()
        expenseDao = db.expenseDao()
    }

    @After
    fun tearDown() = db.close()

    // --- джойны --------------------------------------------------------------

    @Test
    fun `observeAll отдаёт ExpenseRow с именем категории и подкатегории`() = runTest {
        val categoryId = insertCategory("Кафе")
        val subId = insertSubcategory(categoryId, "Кофе")
        val id = expenseDao.insert(expense(categoryId, subId, day1, amountMinor = 25_000))

        val row = expenseDao.observeAll().first().single()

        assertThat(row.id).isEqualTo(id)
        assertThat(row.categoryId).isEqualTo(categoryId)
        assertThat(row.categoryName).isEqualTo("Кафе")
        assertThat(row.subcategoryId).isEqualTo(subId)
        assertThat(row.subcategoryName).isEqualTo("Кофе")
        assertThat(row.epochDay).isEqualTo(day1.toEpochDay())
        assertThat(row.amountMinor).isEqualTo(25_000L)
        assertThat(row.currency).isEqualTo("RUB")
    }

    @Test
    fun `LEFT JOIN отдаёт null в subcategoryName если подкатегория не выбрана`() = runTest {
        val categoryId = insertCategory("Прочее")
        insertSubcategory(categoryId, "Непредвиденное") // существует, но траты её не используют
        expenseDao.insert(expense(categoryId, subcategoryId = null, date = day2))

        val row = expenseDao.getAll().single()

        // JOIN по категории обязателен, LEFT JOIN по подкатегории — нет.
        assertThat(row.categoryName).isEqualTo("Прочее")
        assertThat(row.subcategoryId).isNull()
        assertThat(row.subcategoryName).isNull()
    }

    @Test
    fun `observeAll сортирует по epochDay DESC затем по createdAt DESC`() = runTest {
        val categoryId = insertCategory("Продукты")
        val older = expenseDao.insert(expense(categoryId, date = day1, createdAt = 100))
        val sameDayEarly = expenseDao.insert(expense(categoryId, date = day3, createdAt = 100))
        val sameDayLate = expenseDao.insert(expense(categoryId, date = day3, createdAt = 200))

        val ids = expenseDao.observeAll().first().map { it.id }

        assertThat(ids).containsExactly(sameDayLate, sameDayEarly, older).inOrder()
    }

    // --- внешние ключи -------------------------------------------------------

    @Test
    fun `вставка траты с несуществующим categoryId нарушает внешний ключ`() = runTest {
        val thrown = catching { expenseDao.insert(expense(categoryId = 777L)) }

        assertThat(thrown).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(expenseDao.count()).isEqualTo(0)
    }

    @Test
    fun `вставка траты с несуществующим subcategoryId нарушает внешний ключ`() = runTest {
        val categoryId = insertCategory("Продукты")

        val thrown = catching { expenseDao.insert(expense(categoryId, subcategoryId = 999L)) }

        assertThat(thrown).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(expenseDao.count()).isEqualTo(0)
    }

    @Test
    fun `RESTRICT - прямое удаление категории с тратами бросает SQLiteConstraintException`() = runTest {
        val categoryId = insertCategory("Продукты")
        expenseDao.insert(expense(categoryId, date = day1))

        val thrown = catching { categoryDao.deleteCategory(categoryId) }

        assertThat(thrown).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(categoryDao.getAll().map { it.id }).containsExactly(categoryId)
        assertThat(expenseDao.count()).isEqualTo(1)
    }

    @Test
    fun `RESTRICT не мешает удалить категорию без трат`() = runTest {
        val withExpense = insertCategory("Продукты")
        val empty = insertCategory("Прочее")
        expenseDao.insert(expense(withExpense, date = day1))

        categoryDao.deleteCategory(empty)

        assertThat(categoryDao.getAll().map { it.id }).containsExactly(withExpense)
    }

    @Test
    fun `SET NULL - удаление подкатегории обнуляет subcategoryId в трате, а не удаляет её`() = runTest {
        val categoryId = insertCategory("Кафе")
        val subId = insertSubcategory(categoryId, "Кофе")
        val expenseId = expenseDao.insert(expense(categoryId, subId, day1))

        categoryDao.deleteSubcategory(subId)

        // Трата остаётся жива, ссылка на подкатегорию просто обнуляется (ForeignKey.SET_NULL).
        assertThat(expenseDao.count()).isEqualTo(1)
        val entity = expenseDao.getById(expenseId)
        assertThat(entity).isNotNull()
        assertThat(entity!!.subcategoryId).isNull()
        assertThat(entity.categoryId).isEqualTo(categoryId)

        val row = expenseDao.getAll().single()
        assertThat(row.subcategoryName).isNull()
        assertThat(row.categoryName).isEqualTo("Кафе")
    }

    @Test
    fun `CASCADE - удаление категории удаляет её подкатегории`() = runTest {
        val categoryId = insertCategory("Кафе")
        val otherId = insertCategory("Продукты")
        insertSubcategory(categoryId, "Кофе")
        insertSubcategory(categoryId, "Обед")
        val survivor = insertSubcategory(otherId, "Супермаркет")

        categoryDao.deleteCategory(categoryId)

        assertThat(categoryDao.getSubcategories(categoryId)).isEmpty()
        assertThat(categoryDao.observeAllSubcategories().first().map { it.id }).containsExactly(survivor)
    }

    // --- OnConflictStrategy.IGNORE -------------------------------------------

    @Test
    fun `insertCategory с дублирующимся именем возвращает -1`() = runTest {
        val first = categoryDao.insertCategory(CategoryEntity(name = "Продукты", icon = "🛒", colorArgb = color))
        val second = categoryDao.insertCategory(CategoryEntity(name = "Продукты", icon = "📦", colorArgb = color))

        assertThat(first).isGreaterThan(0L)
        assertThat(second).isEqualTo(-1L)
        assertThat(categoryDao.count()).isEqualTo(1)
        // Первая запись не перезаписана.
        assertThat(categoryDao.findCategoryByName("Продукты")!!.icon).isEqualTo("🛒")
    }

    @Test
    fun `insertCategories возвращает -1 только для конфликтующих строк`() = runTest {
        categoryDao.insertCategory(CategoryEntity(name = "Продукты", icon = "🛒", colorArgb = color))

        val ids = categoryDao.insertCategories(
            listOf(
                CategoryEntity(name = "Продукты", icon = "🛒", colorArgb = color), // дубликат
                CategoryEntity(name = "Транспорт", icon = "🚇", colorArgb = color),
            )
        )

        assertThat(ids).hasSize(2)
        assertThat(ids[0]).isEqualTo(-1L)
        assertThat(ids[1]).isGreaterThan(0L)
        assertThat(categoryDao.count()).isEqualTo(2)
    }

    @Test
    fun `insertSubcategory с дублирующимся именем внутри категории возвращает -1`() = runTest {
        val categoryId = insertCategory("Кафе")

        val first = categoryDao.insertSubcategory(SubcategoryEntity(categoryId = categoryId, name = "Кофе"))
        val second = categoryDao.insertSubcategory(SubcategoryEntity(categoryId = categoryId, name = "Кофе"))

        assertThat(first).isGreaterThan(0L)
        assertThat(second).isEqualTo(-1L)
        assertThat(categoryDao.getSubcategories(categoryId)).hasSize(1)
    }

    @Test
    fun `одинаковое имя подкатегории в разных категориях не конфликтует`() = runTest {
        val cafe = insertCategory("Кафе")
        val products = insertCategory("Продукты")

        val cafeCoffee = categoryDao.insertSubcategory(SubcategoryEntity(categoryId = cafe, name = "Кофе"))
        val productsCoffee = categoryDao.insertSubcategory(SubcategoryEntity(categoryId = products, name = "Кофе"))

        assertThat(cafeCoffee).isGreaterThan(0L)
        assertThat(productsCoffee).isGreaterThan(0L)
        assertThat(productsCoffee).isNotEqualTo(cafeCoffee)
    }

    // --- счётчики и очистка --------------------------------------------------

    @Test
    fun `expenseCountForCategory считает только траты своей категории`() = runTest {
        val products = insertCategory("Продукты")
        val transport = insertCategory("Транспорт")
        val empty = insertCategory("Прочее")
        expenseDao.insert(expense(products, date = day1))
        expenseDao.insert(expense(products, date = day2))
        expenseDao.insert(expense(transport, date = day3))

        assertThat(categoryDao.expenseCountForCategory(products)).isEqualTo(2)
        assertThat(categoryDao.expenseCountForCategory(transport)).isEqualTo(1)
        assertThat(categoryDao.expenseCountForCategory(empty)).isEqualTo(0)
    }

    @Test
    fun `expenseCountForSubcategory считает только траты своей подкатегории`() = runTest {
        val categoryId = insertCategory("Кафе")
        val coffee = insertSubcategory(categoryId, "Кофе")
        val lunch = insertSubcategory(categoryId, "Обед")
        expenseDao.insert(expense(categoryId, coffee, day1))
        expenseDao.insert(expense(categoryId, coffee, day2))
        expenseDao.insert(expense(categoryId, subcategoryId = null, date = day3))

        assertThat(categoryDao.expenseCountForSubcategory(coffee)).isEqualTo(2)
        assertThat(categoryDao.expenseCountForSubcategory(lunch)).isEqualTo(0)
    }

    @Test
    fun `count и deleteAll работают на таблице трат`() = runTest {
        val categoryId = insertCategory("Продукты")
        assertThat(expenseDao.count()).isEqualTo(0)
        expenseDao.insert(expense(categoryId, date = day1))
        expenseDao.insert(expense(categoryId, date = day2))
        assertThat(expenseDao.count()).isEqualTo(2)

        expenseDao.deleteAll()

        assertThat(expenseDao.count()).isEqualTo(0)
        assertThat(expenseDao.getAll()).isEmpty()
        // Категории при этом остаются на месте.
        assertThat(categoryDao.count()).isEqualTo(1)
    }

    @Test
    fun `getById возвращает null для несуществующей траты`() = runTest {
        val categoryId = insertCategory("Продукты")
        val id = expenseDao.insert(expense(categoryId, date = day1))

        assertThat(expenseDao.getById(id)).isNotNull()
        assertThat(expenseDao.getById(id + 1_000)).isNull()
    }

    @Test
    fun `delete удаляет одну трату по id`() = runTest {
        val categoryId = insertCategory("Продукты")
        val first = expenseDao.insert(expense(categoryId, date = day1))
        val second = expenseDao.insert(expense(categoryId, date = day2))

        expenseDao.delete(first)

        assertThat(expenseDao.getAll().map { it.id }).containsExactly(second)
    }

    // --- утилиты -------------------------------------------------------------

    private suspend fun insertCategory(name: String, sortOrder: Int = 0): Long =
        categoryDao.insertCategory(
            CategoryEntity(name = name, icon = "🛒", colorArgb = color, sortOrder = sortOrder)
        )

    private suspend fun insertSubcategory(categoryId: Long, name: String, sortOrder: Int = 0): Long =
        categoryDao.insertSubcategory(
            SubcategoryEntity(categoryId = categoryId, name = name, sortOrder = sortOrder)
        )

    private fun expense(
        categoryId: Long,
        subcategoryId: Long? = null,
        date: LocalDate = day1,
        amountMinor: Long = 10_000,
        note: String = "",
        paymentMethod: String = "CARD",
        createdAt: Long = 1_700_000_000_000L,
    ) = ExpenseEntity(
        amountMinor = amountMinor,
        categoryId = categoryId,
        subcategoryId = subcategoryId,
        epochDay = date.toEpochDay(),
        note = note,
        paymentMethod = paymentMethod,
        createdAt = createdAt,
    )

    /** Ловит исключение из suspend-блока: assertThrows из JUnit не работает с suspend-лямбдами. */
    private suspend fun catching(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (t: Throwable) {
            t
        }
}
