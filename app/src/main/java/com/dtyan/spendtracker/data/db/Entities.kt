package com.dtyan.spendtracker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** Категория для пополнений (доходов). Расходные и доходные категории не смешиваются в UI. */
    val isIncome: Boolean = false,
)

@Entity(
    tableName = "subcategories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["categoryId", "name"], unique = true),
    ],
)
data class SubcategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SubcategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["subcategoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["subcategoryId"]),
        Index(value = ["epochDay"]),
        Index(value = ["status"]),
        Index(value = ["importBatchId"]),
        // Дедупликация повторного импорта: одна операция из одного источника — один раз.
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Сумма в копейках. Всегда положительна; расход/доход задаётся полем [type]. */
    val amountMinor: Long,
    val currency: String = "RUB",
    val categoryId: Long,
    val subcategoryId: Long?,
    /** Дата траты как epochDay — сортируемо и без таймзонных сюрпризов. */
    val epochDay: Long,
    val note: String = "",
    val paymentMethod: String = "CARD",
    val createdAt: Long,
    /** EXPENSE / INCOME. */
    val type: String = "EXPENSE",
    /** Происхождение: MANUAL / IMPORT / NOTIFICATION. */
    val source: String = "MANUAL",
    /** Статус: CONFIRMED / PENDING. Ручные — всегда CONFIRMED. */
    val status: String = "CONFIRMED",
    /** Детерминированный ключ операции из источника — для дедупликации. NULL у ручных. */
    val externalId: String? = null,
    /** MCC-код (из выписки). */
    val mcc: Int? = null,
    /** Сырое имя мерчанта до нормализации. */
    val merchantRaw: String? = null,
    /** Код банка-источника. */
    val bank: String? = null,
    /** Точное время операции (мс). epochDay остаётся ведущим полем даты. */
    val operationTimeMillis: Long? = null,
    /** Исходная строка выписки — для переразбора. */
    val rawText: String? = null,
    /** Батч импорта (для отката). */
    val importBatchId: Long? = null,
)

/** Журнал импортов — для показа истории и отката одной кнопкой. */
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val bank: String,
    val sourceKind: String,        // "CSV"
    val fileName: String?,         // только имя, без пути и содержимого
    val rowsTotal: Int,
    val rowsImported: Int,
    val rowsDuplicate: Int,
    val rowsSkipped: Int,
    val periodFromEpochDay: Long?,
    val periodToEpochDay: Long?,
)
