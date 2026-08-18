package com.dtyan.spendtracker.data

import com.dtyan.spendtracker.data.db.CategoryDao
import com.dtyan.spendtracker.data.db.CategoryEntity
import com.dtyan.spendtracker.data.db.ExpenseDao
import com.dtyan.spendtracker.data.db.ExpenseEntity
import com.dtyan.spendtracker.data.db.ExpenseRow
import com.dtyan.spendtracker.data.db.ImportBatchDao
import com.dtyan.spendtracker.data.db.ImportBatchEntity
import com.dtyan.spendtracker.data.db.PendingOperationDao
import com.dtyan.spendtracker.data.db.PendingOperationEntity
import com.dtyan.spendtracker.data.db.SubcategoryEntity
import com.dtyan.spendtracker.domain.model.Category
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.PendingOperation
import com.dtyan.spendtracker.domain.model.PendingStatus
import com.dtyan.spendtracker.domain.model.Subcategory
import com.dtyan.spendtracker.domain.model.SuggestionSource
import com.dtyan.spendtracker.notifications.BankCatalog
import com.dtyan.spendtracker.notifications.MerchantNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Единая точка доступа к данным. UI и статистика работают только через неё.
 */
class ExpenseRepository(
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val importBatchDao: ImportBatchDao? = null,
    private val pendingDao: PendingOperationDao? = null,
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

    // --- Автоучёт из уведомлений ---

    /**
     * Очередь операций из уведомлений с подставленными именами категорий.
     * Пустой поток, если DAO не передан (например, в тестах старой сборки).
     */
    fun observePendingOperations(): Flow<List<PendingOperation>> {
        val dao = pendingDao ?: return flowOf(emptyList())
        return combine(
            dao.observeAll(),
            categoryDao.observeAll(),
            categoryDao.observeAllSubcategories(),
        ) { operations, categories, subcategories ->
            val categoryById = categories.associateBy { it.id }
            val subcategoryById = subcategories.associateBy { it.id }
            operations.map { it.toDomain(categoryById, subcategoryById) }
        }
    }

    /** Счётчик для бейджа на вкладке «Черновики». */
    fun observePendingCount(): Flow<Int> = pendingDao?.observeCount() ?: flowOf(0)

    /**
     * Кладёт распознанную из уведомления операцию в очередь подтверждения.
     *
     * Молча ничего не создаёт в тратах — только очередь (docs/bank-integration.md, §15.5).
     * Дедупликация трёхуровневая:
     *  1. эта же операция уже подтверждена (совпал `externalId` у траты с источником NOTIFICATION);
     *  2. точно такой же текст уведомления уже приходил в последние минуты (обновление пуша);
     *  3. уникальный индекс по `dedupKey` как последний барьер на уровне БД.
     *
     * Категория подставляется по истории подтверждений, затем по встроенному словарю;
     * если ничего не совпало — остаётся пустой, пользователь выберет сам.
     *
     * @return id созданной записи очереди или null, если операция отброшена как дубликат.
     */
    suspend fun addPendingOperation(entry: PendingEntry): Long? {
        val dao = pendingDao ?: return null

        if (expenseDao.countByExternalId(SOURCE_NOTIFICATION, entry.dedupKey) > 0) return null
        val since = entry.postedAtMillis - RECENT_DUPLICATE_WINDOW_MILLIS
        if (dao.countSimilarRecent(entry.bank, entry.amountMinor, entry.rawText, since) > 0) return null

        val suggestion = suggestCategory(entry)
        val id = dao.insertIgnore(
            PendingOperationEntity(
                dedupKey = entry.dedupKey,
                packageName = entry.packageName,
                bank = entry.bank,
                amountMinor = entry.amountMinor,
                currency = entry.currency,
                type = entry.type.name,
                merchant = entry.merchant,
                cardMask = entry.cardMask,
                postedAt = entry.postedAtMillis,
                epochDay = entry.date.toEpochDay(),
                status = if (entry.recognized) PendingStatus.PENDING.name else PendingStatus.UNPARSED.name,
                suggestedCategoryId = suggestion?.categoryId,
                suggestedSubcategoryId = suggestion?.subcategoryId,
                suggestionSource = suggestion?.source?.name,
                title = entry.title,
                rawText = entry.rawText,
                createdAt = System.currentTimeMillis(),
            )
        )
        return id.takeIf { it > 0 }
    }

    suspend fun getPendingOperation(id: Long): PendingOperation? {
        val dao = pendingDao ?: return null
        val entity = dao.getById(id) ?: return null
        val categories = categoryDao.getAll().associateBy { it.id }
        val subcategories = categories.keys
            .flatMap { categoryDao.getSubcategories(it) }
            .associateBy { it.id }
        return entity.toDomain(categories, subcategories)
    }

    /** Меняет предложенную категорию прямо в очереди (пользователь поправил до подтверждения). */
    suspend fun setPendingCategory(id: Long, categoryId: Long?, subcategoryId: Long?) {
        val dao = pendingDao ?: return
        val entity = dao.getById(id) ?: return
        dao.update(
            entity.copy(
                suggestedCategoryId = categoryId,
                suggestedSubcategoryId = subcategoryId,
                // Выбор пользователя — это уже не предположение, метку «предположение» снимаем.
                suggestionSource = null,
            )
        )
    }

    /**
     * Подтверждает операцию: создаёт настоящую трату и убирает её из очереди.
     *
     * @param draft итоговые данные (пользователь мог поправить сумму, дату, категорию).
     * @return [ConfirmResult.CONFIRMED] с id траты, либо причина отказа.
     */
    suspend fun confirmPendingOperation(id: Long, draft: ExpenseDraft): ConfirmResult {
        val dao = pendingDao ?: return ConfirmResult.NotFound
        val entity = dao.getById(id) ?: return ConfirmResult.NotFound
        if (draft.amountMinor <= 0) return ConfirmResult.InvalidAmount

        val expenseId = expenseDao.insertIgnore(
            ExpenseEntity(
                amountMinor = draft.amountMinor,
                currency = draft.currency,
                categoryId = draft.categoryId,
                subcategoryId = draft.subcategoryId,
                epochDay = draft.date.toEpochDay(),
                note = draft.note,
                paymentMethod = draft.paymentMethod.name,
                createdAt = System.currentTimeMillis(),
                type = draft.type.name,
                source = SOURCE_NOTIFICATION,
                status = "CONFIRMED",
                externalId = entity.dedupKey,
                merchantRaw = entity.merchant,
                bank = entity.bank,
                operationTimeMillis = entity.postedAt,
                rawText = entity.rawText,
            )
        )
        // Из очереди убираем в любом случае: если трата уже была создана раньше (id = -1),
        // повторно показывать эту операцию не нужно.
        dao.delete(id)
        return if (expenseId > 0) ConfirmResult.Confirmed(expenseId) else ConfirmResult.AlreadyExists
    }

    /**
     * Отклоняет операцию: удаляет из очереди, ничего не создавая.
     * @return данные удалённой записи — их достаточно, чтобы вернуть операцию
     *         через [addPendingOperation], если пользователь нажмёт «Отменить».
     */
    suspend fun rejectPendingOperation(id: Long): PendingEntry? {
        val dao = pendingDao ?: return null
        val entity = dao.getById(id)
        dao.delete(id)
        return entity?.toEntry()
    }

    private fun PendingOperationEntity.toEntry() = PendingEntry(
        dedupKey = dedupKey,
        packageName = packageName,
        bank = bank,
        amountMinor = amountMinor,
        currency = currency,
        type = EntryType.fromName(type),
        merchant = merchant,
        cardMask = cardMask,
        postedAtMillis = postedAt,
        date = LocalDate.ofEpochDay(epochDay),
        recognized = PendingStatus.fromName(status) == PendingStatus.PENDING,
        title = title,
        rawText = rawText,
        paymentMethod = if (EntryType.fromName(type) == EntryType.INCOME) {
            PaymentMethod.TRANSFER
        } else {
            PaymentMethod.CARD
        },
    )

    /** Отклоняет все операции очереди (кнопка «Очистить»). @return сколько удалено. */
    suspend fun rejectAllPendingOperations(): Int {
        val dao = pendingDao ?: return 0
        val count = dao.count()
        dao.deleteAll()
        return count
    }

    /**
     * Отмечает операции очереди, похожие на уже существующие траты: та же сумма, тот же тип,
     * дата в пределах ±[windowDays]. Нужно, чтобы пользователь не завёл трату дважды,
     * когда та же покупка уже пришла из выписки или введена руками.
     *
     * Совпавшая трата «расходуется» — два одинаковых кофе не пометятся дублями против одной.
     *
     * @return id операций очереди, для которых нашёлся кандидат в дубли.
     */
    suspend fun findSuspectedDuplicates(
        operations: List<PendingOperation>,
        windowDays: Int = 1,
    ): Set<Long> {
        if (operations.isEmpty()) return emptySet()
        val days = operations.map { it.date.toEpochDay() }
        val rows = expenseDao.dedupKeysBetween(
            fromDay = days.min() - windowDays,
            toDay = days.max() + windowDays,
        )

        data class Key(val day: Long, val amount: Long, val type: String, var used: Boolean = false)
        val keys = rows.map { Key(it.epochDay, it.amountMinor, it.type) }

        val result = LinkedHashSet<Long>()
        operations.forEach { op ->
            val day = op.date.toEpochDay()
            val match = keys.firstOrNull {
                !it.used && it.type == op.type.name && it.amount == op.amountMinor &&
                    kotlin.math.abs(it.day - day) <= windowDays
            }
            if (match != null) {
                match.used = true
                result.add(op.id)
            }
        }
        return result
    }

    /**
     * Подбирает категорию для операции из уведомления.
     * Приоритет: собственная история подтверждений → встроенный словарь мерчантов.
     * Ничего не подошло — возвращаем null: «Прочее» молча не подставляем (§12.4).
     */
    private suspend fun suggestCategory(entry: PendingEntry): CategorySuggestion? {
        // Пополнения кладём в доходную категорию сразу — она в приложении одна.
        if (entry.type == EntryType.INCOME) {
            val income = categoryDao.findCategoryByName("Пополнения") ?: return null
            return CategorySuggestion(income.id, null, SuggestionSource.DICTIONARY)
        }

        val merchant = entry.merchant
        if (!merchant.isNullOrBlank()) {
            val history = expenseDao.merchantHistory()
                .firstOrNull { MerchantNormalizer.matches(merchant, it.merchantRaw) }
            if (history != null) {
                return CategorySuggestion(history.categoryId, history.subcategoryId, SuggestionSource.HISTORY)
            }
        }

        val name = entry.suggestedCategoryName ?: return null
        val category = categoryDao.findCategoryByName(name) ?: return null
        val subcategory = entry.suggestedSubcategoryName
            ?.let { categoryDao.findSubcategoryByName(category.id, it) }
        return CategorySuggestion(category.id, subcategory?.id, SuggestionSource.DICTIONARY)
    }

    private fun PendingOperationEntity.toDomain(
        categories: Map<Long, CategoryEntity>,
        subcategories: Map<Long, SubcategoryEntity>,
    ): PendingOperation {
        val category = suggestedCategoryId?.let { categories[it] }
        val subcategory = suggestedSubcategoryId?.let { subcategories[it] }
        return PendingOperation(
            id = id,
            bankCode = bank,
            bankTitle = BankCatalog.title(bank),
            amountMinor = amountMinor,
            currency = currency,
            type = EntryType.fromName(type),
            merchant = merchant,
            cardMask = cardMask,
            dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(postedAt), ZoneId.systemDefault()),
            status = PendingStatus.fromName(status),
            categoryId = category?.id,
            categoryName = category?.name,
            categoryIcon = category?.icon,
            categoryColorArgb = category?.colorArgb,
            subcategoryId = subcategory?.id,
            subcategoryName = subcategory?.name,
            suggestionSource = SuggestionSource.fromName(suggestionSource),
            notificationTitle = title,
            rawText = rawText,
        )
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

/** Источник трат, созданных из уведомлений. */
private const val SOURCE_NOTIFICATION = "NOTIFICATION"

/** Окно, в котором повтор уведомления с тем же текстом считается тем же событием. */
private const val RECENT_DUPLICATE_WINDOW_MILLIS = 5 * 60 * 1000L

/**
 * Операция из уведомления, готовая к постановке в очередь.
 * Как и [ImportEntry], нейтральна к парсеру: разбор живёт в пакете `notifications`.
 */
data class PendingEntry(
    val dedupKey: String,
    val packageName: String,
    /** Код банка. */
    val bank: String,
    val amountMinor: Long,
    val currency: String,
    val type: EntryType,
    val merchant: String?,
    val cardMask: String?,
    val postedAtMillis: Long,
    val date: LocalDate,
    /** false — сумма есть, но тип операции не распознан: уйдёт в очередь как «не распознано». */
    val recognized: Boolean,
    val title: String?,
    val rawText: String,
    val paymentMethod: PaymentMethod,
    /** Подсказка категории из словаря мерчантов (имена, id резолвятся репозиторием). */
    val suggestedCategoryName: String? = null,
    val suggestedSubcategoryName: String? = null,
)

/** Результат подтверждения операции из очереди. */
sealed interface ConfirmResult {
    data class Confirmed(val expenseId: Long) : ConfirmResult

    /** Такая трата уже была создана раньше — из очереди операция убрана. */
    data object AlreadyExists : ConfirmResult
    data object NotFound : ConfirmResult
    data object InvalidAmount : ConfirmResult
}

/** Внутреннее представление подобранной категории. */
private data class CategorySuggestion(
    val categoryId: Long,
    val subcategoryId: Long?,
    val source: SuggestionSource,
)

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
