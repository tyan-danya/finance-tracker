package com.dtyan.spendtracker.data

import com.dtyan.spendtracker.data.db.CategoryDao
import com.dtyan.spendtracker.data.db.CategoryEntity
import com.dtyan.spendtracker.data.db.ExpenseDao
import com.dtyan.spendtracker.data.db.ExpenseEntity
import com.dtyan.spendtracker.data.db.ExpenseRow
import com.dtyan.spendtracker.data.db.ImportBatchDao
import com.dtyan.spendtracker.data.db.ImportBatchEntity
import com.dtyan.spendtracker.data.db.SubcategoryEntity
import com.dtyan.spendtracker.domain.model.Category
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.Subcategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Единая точка доступа к данным. UI и статистика работают только через неё.
 */
class ExpenseRepository(
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val importBatchDao: ImportBatchDao? = null,
) {

    fun observeExpenses(): Flow<List<ExpenseRecord>> =
        expenseDao.observeAll().map { rows -> rows.map { it.toRecord() } }

    /**
     * @param income null — все категории; true — только доходные (пополнения);
     *               false — только расходные. По умолчанию null, чтобы не менять поведение
     *               существующих вызовов.
     */
    fun observeCategoryTree(
        includeArchived: Boolean = false,
        income: Boolean? = null,
    ): Flow<List<CategoryTree>> =
        combine(
            categoryDao.observeAll(),
            categoryDao.observeAllSubcategories(),
        ) { categories, subcategories ->
            val bySubParent = subcategories.groupBy { it.categoryId }
            categories
                .filter { includeArchived || !it.archived }
                .filter { income == null || it.isIncome == income }
                .map { category ->
                    CategoryTree(
                        category = category.toDomain(),
                        subcategories = (bySubParent[category.id] ?: emptyList())
                            .filter { includeArchived || !it.archived }
                            .map { it.toDomain() },
                    )
                }
        }

    suspend fun getAllExpenses(): List<ExpenseRecord> = expenseDao.getAll().map { it.toRecord() }

    /** Заполняет базу дефолтными категориями, если она пуста. Идемпотентно. */
    suspend fun seedDefaultsIfEmpty() {
        if (categoryDao.count() > 0) return
        DefaultCategories.tree.forEachIndexed { index, seed ->
            // Каждая категория с подкатегориями вставляется одной транзакцией:
            // при сбое посреди сида база не останется в полузаполненном состоянии.
            categoryDao.insertCategoryWithSubcategories(
                category = CategoryEntity(
                    name = seed.name,
                    icon = seed.icon,
                    colorArgb = seed.colorArgb,
                    isBuiltIn = true,
                    sortOrder = index,
                    isIncome = seed.isIncome,
                ),
                subcategoryNames = seed.subcategories,
            )
        }
    }

    suspend fun addExpense(draft: ExpenseDraft): Long = expenseDao.insert(draft.toEntity())

    suspend fun updateExpense(draft: ExpenseDraft) {
        val id = requireNotNull(draft.id) { "updateExpense требует id" }
        val existing = expenseDao.getById(id) ?: return
        expenseDao.update(draft.toEntity().copy(id = id, createdAt = existing.createdAt))
    }

    suspend fun deleteExpense(id: Long) = expenseDao.delete(id)

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    /**
     * Создаёт категорию. Если категория с таким именем уже есть — возвращает её id
     * (уникальный индекс + IGNORE делают вставку безопасной).
     */
    suspend fun addCategory(name: String, icon: String, colorArgb: Int): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Название категории не может быть пустым" }
        val existing = categoryDao.findCategoryByName(trimmed)
        if (existing != null) return existing.id
        val id = categoryDao.insertCategory(
            CategoryEntity(
                name = trimmed,
                icon = icon,
                colorArgb = colorArgb,
                isBuiltIn = false,
                sortOrder = Int.MAX_VALUE,
            )
        )
        return if (id > 0) id else categoryDao.findCategoryByName(trimmed)?.id ?: -1L
    }

    suspend fun addSubcategory(categoryId: Long, name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Название подкатегории не может быть пустым" }
        val existing = categoryDao.findSubcategoryByName(categoryId, trimmed)
        if (existing != null) return existing.id
        val id = categoryDao.insertSubcategory(
            SubcategoryEntity(
                categoryId = categoryId,
                name = trimmed,
                isBuiltIn = false,
                sortOrder = Int.MAX_VALUE,
            )
        )
        return if (id > 0) id else categoryDao.findSubcategoryByName(categoryId, trimmed)?.id ?: -1L
    }

    /**
     * Переименовывает/перекрашивает категорию.
     * @return true — успех; false — если новое имя занято другой категорией
     *         (на `name` висит уникальный индекс, слепой UPDATE бросил бы исключение).
     * @throws IllegalArgumentException если имя пустое.
     */
    suspend fun renameCategory(id: Long, name: String, icon: String, colorArgb: Int): Boolean {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Название категории не может быть пустым" }
        val current = categoryDao.getAll().firstOrNull { it.id == id } ?: return false
        val clash = categoryDao.findCategoryByName(trimmed)
        if (clash != null && clash.id != id) return false
        categoryDao.updateCategory(current.copy(name = trimmed, icon = icon, colorArgb = colorArgb))
        return true
    }

    suspend fun setCategoryArchived(id: Long, archived: Boolean) =
        categoryDao.setCategoryArchived(id, archived)

    suspend fun setSubcategoryArchived(id: Long, archived: Boolean) =
        categoryDao.setSubcategoryArchived(id, archived)

    /**
     * Удаляет категорию только если на неё нет ссылок из трат — иначе история сломается.
     * @return true, если удаление прошло; false — если категория используется.
     */
    suspend fun deleteCategoryIfUnused(id: Long): Boolean {
        if (categoryDao.expenseCountForCategory(id) > 0) return false
        categoryDao.deleteCategory(id)
        return true
    }

    suspend fun deleteSubcategoryIfUnused(id: Long): Boolean {
        if (categoryDao.expenseCountForSubcategory(id) > 0) return false
        categoryDao.deleteSubcategory(id)
        return true
    }

    // --- Импорт выписки ---

    fun observeImportBatches(): Flow<List<ImportBatchEntity>> =
        importBatchDao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Импортирует выбранные операции одним батчем с дедупликацией.
     *
     * Дедупликация двухуровневая:
     *  1. против уже импортированных операций того же источника (по `externalId`);
     *  2. против повторов внутри самого файла.
     *
     * Категории/подкатегории резолвятся по имени; если имя не найдено — создаются.
     * Если предложенной категории нет вовсе — используется запасная («Прочее» / «Пополнения»).
     *
     * @return сводка импорта (сколько добавлено, сколько дублей и пропущено).
     */
    suspend fun importEntries(
        entries: List<ImportEntry>,
        bank: String,
        fileName: String?,
        sourceKind: String = "CSV",
        source: String = "IMPORT",
    ): ImportSummary {
        val existing = expenseDao.existingExternalIds(source).toHashSet()
        val seenInFile = HashSet<String>()

        val toInsert = ArrayList<ImportEntry>()
        var dupInDb = 0
        var dupInFile = 0
        for (e in entries) {
            when {
                e.externalId in existing -> dupInDb++
                !seenInFile.add(e.externalId) -> dupInFile++
                else -> toInsert.add(e)
            }
        }

        val fromDay = toInsert.minOfOrNull { it.date.toEpochDay() }
        val toDay = toInsert.maxOfOrNull { it.date.toEpochDay() }
        val now = System.currentTimeMillis()

        val batchId = importBatchDao?.insert(
            ImportBatchEntity(
                createdAt = now,
                bank = bank,
                sourceKind = sourceKind,
                fileName = fileName,
                rowsTotal = entries.size,
                rowsImported = toInsert.size,
                rowsDuplicate = dupInDb + dupInFile,
                rowsSkipped = 0,
                periodFromEpochDay = fromDay,
                periodToEpochDay = toDay,
            )
        )

        var imported = 0
        for (e in toInsert) {
            val categoryId = resolveCategoryId(e.suggestedCategory, e.type)
            val subcategoryId = e.suggestedSubcategory
                ?.let { resolveSubcategoryId(categoryId, it) }
            val id = expenseDao.insertIgnore(
                ExpenseEntity(
                    amountMinor = e.amountMinor,
                    currency = e.currency,
                    categoryId = categoryId,
                    subcategoryId = subcategoryId,
                    epochDay = e.date.toEpochDay(),
                    note = e.note,
                    paymentMethod = e.paymentMethod.name,
                    createdAt = now,
                    type = e.type.name,
                    source = source,
                    status = "CONFIRMED",
                    externalId = e.externalId,
                    mcc = e.mcc,
                    merchantRaw = e.merchantRaw,
                    bank = bank,
                    operationTimeMillis = e.operationTimeMillis,
                    rawText = e.rawText,
                    importBatchId = batchId,
                )
            )
            if (id > 0) imported++
        }

        return ImportSummary(
            batchId = batchId,
            total = entries.size,
            imported = imported,
            duplicatesInDb = dupInDb,
            duplicatesInFile = dupInFile,
        )
    }

    /**
     * Проверяет каждую операцию на дублирование ДО импорта, чтобы вынести спорные на решение
     * пользователя. Два уровня:
     *  - [DuplicateVerdict.ALREADY_IMPORTED] — точный повтор ранее импортированной операции
     *    (совпал `externalId`); по умолчанию не импортируется;
     *  - [DuplicateVerdict.SUSPECTED] — похожа на уже существующую запись ЛЮБОГО источника
     *    (в т.ч. добавленную вручную): та же сумма, тот же тип, дата в пределах ±[windowDays].
     *    Пользователь решает сам.
     *
     * Совпавшая существующая запись «расходуется», поэтому два одинаковых платежа в один день
     * не будут оба помечены дублями против одной ручной траты.
     *
     * @return вердикт для каждой записи в том же порядке, что и [entries].
     */
    suspend fun checkDuplicates(
        entries: List<ImportEntry>,
        windowDays: Int = 1,
        source: String = "IMPORT",
    ): List<DuplicateVerdict> {
        val imported = expenseDao.existingExternalIds(source).toHashSet()

        // Ключи существующих записей с пометкой «занята».
        data class Key(val day: Long, val amount: Long, val type: String, var used: Boolean = false)
        val keys = expenseDao.allDedupKeys().map { Key(it.epochDay, it.amountMinor, it.type) }

        return entries.map { e ->
            if (e.externalId in imported) return@map DuplicateVerdict.ALREADY_IMPORTED
            val day = e.date.toEpochDay()
            val typeName = e.type.name
            val match = keys.firstOrNull {
                !it.used && it.type == typeName && it.amount == e.amountMinor &&
                    kotlin.math.abs(it.day - day) <= windowDays
            }
            if (match != null) {
                match.used = true
                DuplicateVerdict.SUSPECTED
            } else {
                DuplicateVerdict.NONE
            }
        }
    }

    /** Откат импорта: удаляет все операции батча и запись журнала. */
    suspend fun undoImport(batchId: Long): Int {
        val removed = expenseDao.deleteByBatch(batchId)
        importBatchDao?.delete(batchId)
        return removed
    }

    private suspend fun resolveCategoryId(name: String?, type: EntryType): Long {
        val income = type == EntryType.INCOME
        val target = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (income) "Пополнения" else "Прочее"
        categoryDao.findCategoryByName(target)?.let { return it.id }
        // Фолбэк, если предложенное имя не совпало ни с одной категорией.
        val fallback = if (income) "Пополнения" else "Прочее"
        categoryDao.findCategoryByName(fallback)?.let { return it.id }
        // Категории вообще нет (например, база не засеяна) — создаём.
        val created = categoryDao.insertCategory(
            CategoryEntity(
                name = target,
                icon = if (income) "💰" else "📦",
                colorArgb = 0xFF9E9E9E.toInt(),
                isBuiltIn = false,
                sortOrder = Int.MAX_VALUE,
                isIncome = income,
            )
        )
        return if (created > 0) created else categoryDao.findCategoryByName(target)!!.id
    }

    private suspend fun resolveSubcategoryId(categoryId: Long, name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        categoryDao.findSubcategoryByName(categoryId, trimmed)?.let { return it.id }
        val id = categoryDao.insertSubcategory(
            SubcategoryEntity(
                categoryId = categoryId,
                name = trimmed,
                isBuiltIn = false,
                sortOrder = Int.MAX_VALUE,
            )
        )
        return if (id > 0) id else categoryDao.findSubcategoryByName(categoryId, trimmed)?.id
    }
}

/** Нейтральная к парсеру запись для импорта (пакет data не зависит от importer). */
data class ImportEntry(
    val amountMinor: Long,
    val type: EntryType,
    val date: LocalDate,
    val note: String,
    val suggestedCategory: String?,
    val suggestedSubcategory: String?,
    /** Ключ дедупликации. */
    val externalId: String,
    val mcc: Int? = null,
    val merchantRaw: String? = null,
    val operationTimeMillis: Long? = null,
    val rawText: String? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val currency: String = "RUB",
)

data class ImportSummary(
    val batchId: Long?,
    val total: Int,
    val imported: Int,
    val duplicatesInDb: Int,
    val duplicatesInFile: Int,
) {
    val duplicates: Int get() = duplicatesInDb + duplicatesInFile
}

/** Результат проверки операции на дубль перед импортом. */
enum class DuplicateVerdict {
    /** Уникальна — можно импортировать. */
    NONE,

    /** Точный повтор ранее импортированной операции — по умолчанию пропускается. */
    ALREADY_IMPORTED,

    /** Похожа на уже существующую запись (в т.ч. добавленную вручную) — требует решения. */
    SUSPECTED,
}

// --- мапперы ---

internal fun ExpenseRow.toRecord() = ExpenseRecord(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    categoryId = categoryId,
    categoryName = categoryName,
    subcategoryId = subcategoryId,
    subcategoryName = subcategoryName,
    date = LocalDate.ofEpochDay(epochDay),
    note = note,
    paymentMethod = PaymentMethod.fromName(paymentMethod),
    createdAt = createdAt,
    type = EntryType.fromName(type),
)

internal fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    icon = icon,
    colorArgb = colorArgb,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
    archived = archived,
    isIncome = isIncome,
)

internal fun SubcategoryEntity.toDomain() = Subcategory(
    id = id,
    categoryId = categoryId,
    name = name,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
    archived = archived,
)

internal fun ExpenseDraft.toEntity() = ExpenseEntity(
    id = id ?: 0,
    amountMinor = amountMinor,
    currency = currency,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    epochDay = date.toEpochDay(),
    note = note,
    paymentMethod = paymentMethod.name,
    createdAt = System.currentTimeMillis(),
    type = type.name,
)
