package com.dtyan.spendtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        SubcategoryEntity::class,
        ExpenseEntity::class,
        ImportBatchEntity::class,
        PendingOperationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        private const val NAME = "spendtracker.db"

        /**
         * Миграция 1 → 2: доходы/пополнения, поля импорта и дедупликации, журнал импортов.
         * Только ALTER TABLE ADD COLUMN и CREATE — без пересоздания `expenses`, данные сохраняются.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Новые колонки категорий.
                db.execSQL("ALTER TABLE categories ADD COLUMN isIncome INTEGER NOT NULL DEFAULT 0")

                // Новые колонки трат.
                db.execSQL("ALTER TABLE expenses ADD COLUMN type TEXT NOT NULL DEFAULT 'EXPENSE'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN mcc INTEGER")
                db.execSQL("ALTER TABLE expenses ADD COLUMN merchantRaw TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN bank TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN operationTimeMillis INTEGER")
                db.execSQL("ALTER TABLE expenses ADD COLUMN rawText TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN importBatchId INTEGER")

                // Индексы (имена — как генерирует Room, чтобы схема совпала с ожидаемой).
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_status ON expenses (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_importBatchId ON expenses (importBatchId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_source_externalId ON expenses (source, externalId)")

                // Журнал импортов.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_batches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        bank TEXT NOT NULL,
                        sourceKind TEXT NOT NULL,
                        fileName TEXT,
                        rowsTotal INTEGER NOT NULL,
                        rowsImported INTEGER NOT NULL,
                        rowsDuplicate INTEGER NOT NULL,
                        rowsSkipped INTEGER NOT NULL,
                        periodFromEpochDay INTEGER,
                        periodToEpochDay INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Миграция 2 → 3: очередь операций, распознанных из банковских уведомлений.
         * Только CREATE — существующие таблицы не трогаются вовсе.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_operations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        dedupKey TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        bank TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        type TEXT NOT NULL,
                        merchant TEXT,
                        cardMask TEXT,
                        postedAt INTEGER NOT NULL,
                        epochDay INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        suggestedCategoryId INTEGER,
                        suggestedSubcategoryId INTEGER,
                        suggestionSource TEXT,
                        title TEXT,
                        rawText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_operations_dedupKey " +
                        "ON pending_operations (dedupKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_operations_postedAt " +
                        "ON pending_operations (postedAt)"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // RESTRICT/SET NULL работают только при включённых внешних ключах.
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
