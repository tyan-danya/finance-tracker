package com.dtyan.spendtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Ключ операции для эвристического поиска дублей (без имён и мерчантов). */
data class DedupKeyRow(
    val epochDay: Long,
    val amountMinor: Long,
    val type: String,
)

/** Мерчант подтверждённой траты и выбранная для него категория — основа автокатегоризации. */
data class MerchantHistoryRow(
    val merchantRaw: String,
    val categoryId: Long,
    val subcategoryId: Long?,
)

/** Плоская строка джойна трат с именами категорий. */
data class ExpenseRow(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val categoryName: String,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val epochDay: Long,
    val note: String,
    val paymentMethod: String,
    val createdAt: Long,
    val type: String,
)

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM subcategories ORDER BY sortOrder, name")
    fun observeAllSubcategories(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId ORDER BY sortOrder, name")
    suspend fun getSubcategories(categoryId: Long): List<SubcategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubcategory(subcategory: SubcategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubcategories(subcategories: List<SubcategoryEntity>): List<Long>

    /**
     * Транзакционная вставка категории вместе с подкатегориями.
     * Всё либо применяется целиком, либо откатывается — база не остаётся полузасеянной.
     * @return id вставленной категории или -1, если категория с таким именем уже была.
     */
    @Transaction
    suspend fun insertCategoryWithSubcategories(
        category: CategoryEntity,
        subcategoryNames: List<String>,
    ): Long {
        val categoryId = insertCategory(category)
        if (categoryId <= 0) return -1
        insertSubcategories(
            subcategoryNames.mapIndexed { index, name ->
                SubcategoryEntity(
                    categoryId = categoryId,
                    name = name,
                    isBuiltIn = true,
                    sortOrder = index,
                )
            }
        )
        return categoryId
    }

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Update
    suspend fun updateSubcategory(subcategory: SubcategoryEntity)

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findCategoryByName(name: String): CategoryEntity?

    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId AND name = :name LIMIT 1")
    suspend fun findSubcategoryByName(categoryId: Long, name: String): SubcategoryEntity?

    @Query("SELECT COUNT(*) FROM expenses WHERE categoryId = :categoryId")
    suspend fun expenseCountForCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM expenses WHERE subcategoryId = :subcategoryId")
    suspend fun expenseCountForSubcategory(subcategoryId: Long): Int

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query("DELETE FROM subcategories WHERE id = :subcategoryId")
    suspend fun deleteSubcategory(subcategoryId: Long)

    @Query("UPDATE categories SET archived = :archived WHERE id = :categoryId")
    suspend fun setCategoryArchived(categoryId: Long, archived: Boolean)

    @Query("UPDATE subcategories SET archived = :archived WHERE id = :subcategoryId")
    suspend fun setSubcategoryArchived(subcategoryId: Long, archived: Boolean)
}

@Dao
interface ExpenseDao {

    @Transaction
    @Query(
        """
        SELECT e.id AS id,
               e.amountMinor AS amountMinor,
               e.currency AS currency,
               e.categoryId AS categoryId,
               c.name AS categoryName,
               e.subcategoryId AS subcategoryId,
               s.name AS subcategoryName,
               e.epochDay AS epochDay,
               e.note AS note,
               e.paymentMethod AS paymentMethod,
               e.createdAt AS createdAt,
               e.type AS type
        FROM expenses e
        JOIN categories c ON c.id = e.categoryId
        LEFT JOIN subcategories s ON s.id = e.subcategoryId
        ORDER BY e.epochDay DESC, e.createdAt DESC
        """
    )
    fun observeAll(): Flow<List<ExpenseRow>>

    @Transaction
    @Query(
        """
        SELECT e.id AS id,
               e.amountMinor AS amountMinor,
               e.currency AS currency,
               e.categoryId AS categoryId,
               c.name AS categoryName,
               e.subcategoryId AS subcategoryId,
               s.name AS subcategoryName,
               e.epochDay AS epochDay,
               e.note AS note,
               e.paymentMethod AS paymentMethod,
               e.createdAt AS createdAt,
               e.type AS type
        FROM expenses e
        JOIN categories c ON c.id = e.categoryId
        LEFT JOIN subcategories s ON s.id = e.subcategoryId
        ORDER BY e.epochDay DESC, e.createdAt DESC
        """
    )
    suspend fun getAll(): List<ExpenseRow>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    /** Вставка при импорте: дубликат по (source, externalId) молча пропускается (вернёт -1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(expense: ExpenseEntity): Long

    /** Есть ли уже трата с таким ключом операции — точечная проверка вместо выборки всех ключей. */
    @Query("SELECT COUNT(*) FROM expenses WHERE source = :source AND externalId = :externalId")
    suspend fun countByExternalId(source: String, externalId: String): Int

    /** Уже импортированные ключи операций данного источника — для дедупликации до вставки. */
    @Query("SELECT externalId FROM expenses WHERE source = :source AND externalId IS NOT NULL")
    suspend fun existingExternalIds(source: String): List<String>

    /** Компактные ключи всех существующих операций — для эвристического поиска дублей. */
    @Query("SELECT epochDay, amountMinor, type FROM expenses")
    suspend fun allDedupKeys(): List<DedupKeyRow>

    /**
     * Ключи операций за окно дат — для отметки «возможный дубликат» у распознанных уведомлений.
     * Границы включительно.
     */
    @Query("SELECT epochDay, amountMinor, type FROM expenses WHERE epochDay BETWEEN :fromDay AND :toDay")
    suspend fun dedupKeysBetween(fromDay: Long, toDay: Long): List<DedupKeyRow>

    /**
     * История «мерчант → категория» по подтверждённым тратам. Используется для подсказки
     * категории новым операциям того же мерчанта (обучение на подтверждениях, §12.3).
     */
    @Query(
        """
        SELECT merchantRaw, categoryId, subcategoryId FROM expenses
        WHERE merchantRaw IS NOT NULL AND TRIM(merchantRaw) <> ''
        ORDER BY createdAt DESC
        LIMIT 500
        """
    )
    suspend fun merchantHistory(): List<MerchantHistoryRow>

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM expenses WHERE importBatchId = :batchId")
    suspend fun deleteByBatch(batchId: Long): Int

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int
}

@Dao
interface PendingOperationDao {

    @Query("SELECT * FROM pending_operations ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<PendingOperationEntity>>

    /** Счётчик для бейджа на вкладке — отдельным запросом, чтобы не тянуть весь список. */
    @Query("SELECT COUNT(*) FROM pending_operations")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_operations ORDER BY postedAt DESC")
    suspend fun getAll(): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingOperationEntity?

    /** Повтор того же уведомления игнорируется молча (уникальный индекс по dedupKey). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(operation: PendingOperationEntity): Long

    @Update
    suspend fun update(operation: PendingOperationEntity)

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun count(): Int

    /**
     * Сколько раз ровно такое уведомление уже приходило за последние минуты.
     * Система пересылает уведомление при каждом обновлении — так мы гасим повторы,
     * даже если они попали в разные окна `dedupKey`.
     */
    @Query(
        """
        SELECT COUNT(*) FROM pending_operations
        WHERE bank = :bank AND amountMinor = :amountMinor AND rawText = :rawText AND postedAt >= :since
        """
    )
    suspend fun countSimilarRecent(bank: String, amountMinor: Long, rawText: String, since: Long): Int
}

@Dao
interface ImportBatchDao {

    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun delete(id: Long)
}
