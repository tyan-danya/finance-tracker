package com.dtyan.spendtracker.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ConfirmResult
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.PendingEntry
import com.dtyan.spendtracker.data.SettingsStore
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
            when (repository.confirmPendingOperation(operation.id, draft)) {
                is ConfirmResult.Confirmed -> _message.value = PendingMessage.Confirmed(operation.displayTitle)
                ConfirmResult.AlreadyExists -> _message.value = PendingMessage.AlreadyExists
                ConfirmResult.InvalidAmount -> _message.value = PendingMessage.InvalidAmount
                ConfirmResult.NotFound -> Unit
            }
        }
    }

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
            _message.value = PendingMessage.ConfirmedMany(confirmed)
        }
    }

    /** Меняет категорию прямо в очереди — до подтверждения. */
    fun setCategory(operationId: Long, categoryId: Long?, subcategoryId: Long?) {
        viewModelScope.launch {
            repository.setPendingCategory(operationId, categoryId, subcategoryId)
        }
    }

    /** Отклоняет операцию с возможностью отмены (запись возвращается в очередь). */
    fun reject(operation: PendingOperation) {
        viewModelScope.launch {
            val removed = repository.rejectPendingOperation(operation.id)
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
