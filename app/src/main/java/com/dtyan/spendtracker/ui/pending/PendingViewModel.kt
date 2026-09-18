package com.dtyan.spendtracker.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ConfirmResult
import com.dtyan.spendtracker.data.DiagnosticsLog
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.LogStage
import com.dtyan.spendtracker.data.PendingEntry
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.PendingOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Состояние экрана «Черновики» — очереди операций из банковских уведомлений.
 */
data class PendingUiState(
    val operations: List<PendingOperation> = emptyList(),
    /** Операции, похожие на уже существующие траты, — помечаются предупреждением. */
    val duplicateIds: Set<Long> = emptySet(),
    val expenseCategories: List<CategoryTree> = emptyList(),
    val incomeCategories: List<CategoryTree> = emptyList(),
    val autoCaptureEnabled: Boolean = false,
) {
    /** Сколько операций можно подтвердить одним нажатием (сумма разобрана, категория есть). */
    val readyCount: Int get() = operations.count { it.isReadyToConfirm }

    val isEmpty: Boolean get() = operations.isEmpty()

    fun categoriesFor(type: EntryType): List<CategoryTree> =
        if (type == EntryType.INCOME) incomeCategories else expenseCategories
}

/**
 * Вью-модель очереди подтверждения.
 *
 * Ключевое правило: ни один метод не создаёт трату сам по себе — всё только по действию
 * пользователя (подтвердить / подтвердить все / отклонить).
 */
class PendingViewModel(
    private val repository: ExpenseRepository,
    private val settings: SettingsStore,
    private val log: DiagnosticsLog? = null,
) : ViewModel() {

    val state: StateFlow<PendingUiState> = combine(
        repository.observePendingOperations(),
        repository.observeCategoryTree(income = false),
        repository.observeCategoryTree(income = true),
        settings.observe(),
    ) { operations, expenseCategories, incomeCategories, appSettings ->
        PendingUiState(
            operations = operations,
            duplicateIds = repository.findSuspectedDuplicates(operations),
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            autoCaptureEnabled = appSettings.enabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PendingUiState(),
    )

    private val _message = MutableStateFlow<PendingMessage?>(null)
    val message: StateFlow<PendingMessage?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** Подтверждение «как есть»: данными самой операции. Доступно только когда категория выбрана. */
    fun confirm(operation: PendingOperation) {
        val categoryId = operation.categoryId ?: return
        confirm(
            operation = operation,
            draft = ExpenseDraft(
                amountMinor = operation.amountMinor,
                categoryId = categoryId,
                subcategoryId = operation.subcategoryId,
                date = operation.date,
                note = operation.merchant.orEmpty(),
                paymentMethod = if (operation.isIncome) {
                    com.dtyan.spendtracker.domain.model.PaymentMethod.TRANSFER
                } else {
                    com.dtyan.spendtracker.domain.model.PaymentMethod.CARD
                },
                currency = operation.currency,
                type = operation.type,
            ),
        )
    }

    /** Подтверждение с правками пользователя из карточки редактирования. */
    fun confirm(operation: PendingOperation, draft: ExpenseDraft) {
        viewModelScope.launch {
            val result = repository.confirmPendingOperation(operation.id, draft)
            logConfirm(operation, draft, result)
            when (result) {
                is ConfirmResult.Confirmed -> _message.value = PendingMessage.Confirmed(operation.displayTitle)
                ConfirmResult.AlreadyExists -> _message.value = PendingMessage.AlreadyExists
                ConfirmResult.InvalidAmount -> _message.value = PendingMessage.InvalidAmount
                ConfirmResult.NotFound -> Unit
            }
        }
    }

    /**
     * Чем выбор пользователя отличается от предложенного — главный материал для улучшения
     * автокатегоризации, поэтому разница пишется в журнал явно.
     */
    private suspend fun logConfirm(
        operation: PendingOperation,
        draft: ExpenseDraft,
        result: ConfirmResult,
    ) {
        val logger = log ?: return
        val chosenTree = state.value.categoriesFor(draft.type)
            .firstOrNull { it.category.id == draft.categoryId }
        val chosenSub = chosenTree?.subcategories?.firstOrNull { it.id == draft.subcategoryId }
        val suggested = listOfNotNull(operation.categoryName, operation.subcategoryName)
            .joinToString(" / ").ifEmpty { "не подобрана" }
        val picked = listOfNotNull(chosenTree?.category?.name, chosenSub?.name)
            .joinToString(" / ").ifEmpty { "id=${draft.categoryId}" }

        logger.log(
            stage = LogStage.CONFIRM,
            message = when (result) {
                is ConfirmResult.Confirmed -> "Подтверждена операция #${operation.id}: ${operation.displayTitle}"
                ConfirmResult.AlreadyExists -> "Операция #${operation.id} уже была в тратах"
                ConfirmResult.InvalidAmount -> "Не подтверждено: некорректная сумма"
                ConfirmResult.NotFound -> "Не подтверждено: операция не найдена"
            },
            details = buildString {
                appendLine("мерчант: ${operation.merchant ?: "—"}")
                appendLine(
                    "предложено: $suggested" +
                        (operation.suggestionSource?.let { " (${it.title})" } ?: "")
                )
                appendLine("выбрано: $picked")
                appendLine("категорию изменил: ${yesNo(operation.categoryId != draft.categoryId)}")
                appendLine(
                    "сумма: ${MoneyFormat.format(draft.amountMinor)}" +
                        if (draft.amountMinor != operation.amountMinor) {
                            " (было ${MoneyFormat.format(operation.amountMinor)})"
                        } else ""
                )
                appendLine(
                    "дата: ${draft.date}" +
                        if (draft.date != operation.date) " (было ${operation.date})" else ""
                )
                appendLine(
                    "тип: ${draft.type.name}" +
                        if (draft.type != operation.type) " (было ${operation.type.name})" else ""
                )
                appendLine("способ оплаты: ${draft.paymentMethod.name}")
                appendLine("комментарий: ${draft.note.ifBlank { "—" }}")
            },
        )
    }

    private fun yesNo(value: Boolean) = if (value) "да" else "нет"

    /** Подтверждает все операции, у которых есть и сумма, и категория. Остальные остаются в списке. */
    fun confirmAllReady() {
        val ready = state.value.operations.filter { it.isReadyToConfirm }
        if (ready.isEmpty()) return
        viewModelScope.launch {
            var confirmed = 0
            ready.forEach { operation ->
                val categoryId = operation.categoryId ?: return@forEach
                val result = repository.confirmPendingOperation(
                    id = operation.id,
                    draft = ExpenseDraft(
                        amountMinor = operation.amountMinor,
                        categoryId = categoryId,
                        subcategoryId = operation.subcategoryId,
                        date = operation.date,
                        note = operation.merchant.orEmpty(),
                        paymentMethod = if (operation.isIncome) {
                            com.dtyan.spendtracker.domain.model.PaymentMethod.TRANSFER
                        } else {
                            com.dtyan.spendtracker.domain.model.PaymentMethod.CARD
                        },
                        currency = operation.currency,
                        type = operation.type,
                    ),
                )
                if (result is ConfirmResult.Confirmed) confirmed++
            }
            log?.log(
                stage = LogStage.CONFIRM,
                message = "Массовое подтверждение: $confirmed из ${ready.size}",
            )
            _message.value = PendingMessage.ConfirmedMany(confirmed)
        }
    }

    /** Меняет категорию прямо в очереди — до подтверждения. */
    fun setCategory(operationId: Long, categoryId: Long?, subcategoryId: Long?) {
        viewModelScope.launch {
            repository.setPendingCategory(operationId, categoryId, subcategoryId)
            val tree = (state.value.expenseCategories + state.value.incomeCategories)
                .firstOrNull { it.category.id == categoryId }
            log?.log(
                stage = LogStage.CONFIRM,
                message = "Категория операции #$operationId изменена вручную",
                details = "выбрано: ${tree?.category?.name ?: "—"}",
            )
        }
    }

    /**
     * Создаёт категорию прямо из выбора категории — чтобы не уходить в отдельный раздел
     * и не терять карточку операции.
     * @return id созданной (или уже существовавшей с таким именем) категории.
     */
    suspend fun createCategory(name: String, icon: String, colorArgb: Int, isIncome: Boolean): Long {
        val id = repository.addCategory(name, icon, colorArgb, isIncome)
        log?.log(
            stage = LogStage.CONFIRM,
            message = "Создана категория «$name»",
            details = "тип: ${if (isIncome) "пополнения" else "расходы"}, id: $id",
        )
        return id
    }

    /** Создаёт подкатегорию в выбранной категории. */
    suspend fun createSubcategory(categoryId: Long, name: String): Long =
        repository.addSubcategory(categoryId, name)

    /** Отклоняет операцию с возможностью отмены (запись возвращается в очередь). */
    fun reject(operation: PendingOperation) {
        viewModelScope.launch {
            val removed = repository.rejectPendingOperation(operation.id)
            log?.log(
                stage = LogStage.CONFIRM,
                message = "Отклонена операция #${operation.id}: ${operation.displayTitle}",
                details = "сумма: ${MoneyFormat.format(operation.amountMinor)}",
            )
            _message.value = PendingMessage.Rejected(operation.displayTitle, removed)
        }
    }

    /** Возвращает отклонённую операцию обратно в очередь. */
    fun restore(entry: PendingEntry) {
        viewModelScope.launch { repository.addPendingOperation(entry) }
    }

    fun rejectAll() {
        viewModelScope.launch {
            val count = repository.rejectAllPendingOperations()
            log?.log(stage = LogStage.CONFIRM, message = "Очередь очищена: отклонено $count")
            _message.value = PendingMessage.RejectedMany(count)
        }
    }
}

/** Разовое сообщение для снекбара. */
sealed interface PendingMessage {
    data class Confirmed(val title: String) : PendingMessage
    data class ConfirmedMany(val count: Int) : PendingMessage
    data class Rejected(val title: String, val restorable: PendingEntry?) : PendingMessage
    data class RejectedMany(val count: Int) : PendingMessage
    data object AlreadyExists : PendingMessage
    data object InvalidAmount : PendingMessage
}
