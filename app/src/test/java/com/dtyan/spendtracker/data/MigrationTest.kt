package com.dtyan.spendtracker.data

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.dtyan.spendtracker.data.db.AppDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверяет миграцию 1 → 2 напрямую: строим схему v1 руками, кладём данные,
 * применяем MIGRATION_1_2 и убеждаемся, что данные выжили, а новые колонки/таблицы появились.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration-test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = createV1(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("migration-test.db")
    }

    private fun createV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, icon TEXT NOT NULL, colorArgb INTEGER NOT NULL, " +
                "isBuiltIn INTEGER NOT NULL, sortOrder INTEGER NOT NULL, archived INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE subcategories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "categoryId INTEGER NOT NULL, name TEXT NOT NULL, isBuiltIn INTEGER NOT NULL, " +
                "sortOrder INTEGER NOT NULL, archived INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, categoryId INTEGER NOT NULL, " +
                "subcategoryId INTEGER, epochDay INTEGER NOT NULL, note TEXT NOT NULL, " +
                "paymentMethod TEXT NOT NULL, createdAt INTEGER NOT NULL)"
        )
    }

    @Test
    fun `миграция 1 to 2 сохраняет данные и добавляет колонки`() {
        // Данные версии 1.
        db.insert("categories", 0, ContentValues().apply {
            put("id", 1L); put("name", "Продукты"); put("icon", "🛒"); put("colorArgb", -1)
            put("isBuiltIn", 1); put("sortOrder", 0); put("archived", 0)
        })
        db.insert("expenses", 0, ContentValues().apply {
            put("id", 1L); put("amountMinor", 12345L); put("currency", "RUB"); put("categoryId", 1L)
            putNull("subcategoryId"); put("epochDay", 20000L); put("note", "тест")
            put("paymentMethod", "CARD"); put("createdAt", 111L)
        })

        AppDatabase.MIGRATION_1_2.migrate(db)

        // Старая трата на месте и получила дефолты новых колонок.
        db.query("SELECT amountMinor, type, source, status, importBatchId FROM expenses WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(12345L)
            assertThat(c.getString(1)).isEqualTo("EXPENSE")
            assertThat(c.getString(2)).isEqualTo("MANUAL")
            assertThat(c.getString(3)).isEqualTo("CONFIRMED")
            assertThat(c.isNull(4)).isTrue()
        }

        // Новая колонка категорий.
        db.query("SELECT isIncome FROM categories WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }

        // Новая таблица журнала импортов существует и пуста.
        db.query("SELECT COUNT(*) FROM import_batches").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }
}
