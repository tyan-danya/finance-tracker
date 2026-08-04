package com.dtyan.spendtracker.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.Category
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.Subcategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Состояние экрана добавления/редактирования траты.
 * Сумма хранится строкой — ровно так, как её набирает пользователь; парсится при валидации.
 */
data class AddExpenseUiState(
    val amountText: String = "",
    /** Тип операции: расход или пополнение. От него зависит набор категорий. */
    val type: EntryType = EntryType.EXPENSE,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val tree: List<CategoryTree> = emptyList(),
    val isEdit: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** Выставляется после успешного сохранения/удаления — экран закрывается. */
    val finished: Boolean = false,
) {
    val isIncome: Boolean get() = type == EntryType.INCOME

    /** Распознанная сумма в копейках или null. */
    val amountMinor: Long? get() = MoneyFormat.parseToMinor(amountText)

    val selectedCategory: Category?
        get() = tree.firstOrNull { it.category.id == categoryId }?.category

    val subcategories: List<Subcategory>
        get() = tree.firstOrNull { it.category.id == categoryId }?.subcategories.orEmpty()

    val canSave: Boolean
        get() = !saving && (amountMinor ?: 0L) > 0L && categoryId != null
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModel(
    private val repository: ExpenseRepository,
    private val editExpenseId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddExpenseUiState(isEdit = editExpenseId != null))
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    /**
     * Текущий тип операции управляет тем, какое дерево категорий подписано:
     * расходное (income = false) или доходное (income = true).
     */
    private val _type = MutableStateFlow(EntryType.EXPENSE)

    init {
        // Дерево категорий живое и зависит от типа: при смене типа переключаемся
        // на другой набор (расходные ↔ доходные категории).
        viewModelScope.launch {
            _type
                .flatMapLatest { type ->
                    repository.observeCategoryTree(income = type == EntryType.INCOME)
                }
                .collect { tree ->
                    _uiState.update { state ->
                        // Если выбранная категория исчезла (архивирована/удалена/из другого
                        // набора) — сбрасываем выбор.
                        val categoryStillThere = state.categoryId != null &&
                            tree.any { it.category.id == state.categoryId }
                        val subStillThere = state.subcategoryId != null && tree
                            .firstOrNull { it.category.id == state.categoryId }
                            ?.subcategories.orEmpty()
                            .any { it.id == state.subcategoryId }
                        state.copy(
                            tree = tree,
                            categoryId = if (categoryStillThere) state.categoryId else null,
                            subcategoryId = if (subStillThere) state.subcategoryId else null,
                        )
                    }
                }
        }
        if (editExpenseId != null) loadExisting(editExpenseId) else _uiState.update { it.copy(loaded = true) }
    }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            val existing = repository.getAllExpenses().firstOrNull { it.id == id }
            if (existing == null) {
                _uiState.update { it.copy(loaded = true, error = "Трата не найдена") }
                return@launch
            }
            // Сначала переключаем источник дерева на нужный тип, затем подставляем
            // выбранную категорию — так flatMapLatest уже отдаёт правильный набор,
            // и валидация в коллекторе не сбросит категорию из другого набора.
            _type.value = existing.type
            _uiState.update {
                it.copy(
                    amountText = minorToInput(existing.amountMinor),
                    type = existing.type,
                    categoryId = existing.categoryId,
                    subcategoryId = existing.subcategoryId,
                    date = existing.date,
                    note = existing.note,
                    paymentMethod = existing.paymentMethod,
                    loaded = true,
                )
            }
        }
    }

    /**
     * Переключает тип операции. При смене сбрасываем выбранную категорию/подкатегорию —
     * они относятся к другому набору (расходному/доходному).
     */
    fun setType(type: EntryType) {
        if (_type.value == type) return
        _type.value = type
        _uiState.update {
            it.copy(type = type, categoryId = null, subcategoryId = null, error = null)
        }
    }

    fun setAmount(raw: String) {
        // Пропускаем только цифры и один разделитель — иначе легко получить нераспознаваемую строку.
        val filtered = raw.filter { it.isDigit() || it == ',' || it == '.' }
        _uiState.update { it.copy(amountText = filtered, error = null) }
    }

    /** Быстрая прибавка суммы в рублях (кнопки +100 / +500 / +1000). */
    fun addRubles(rubles: Long) {
        _uiState.update { state ->
            val current = MoneyFormat.parseToMinor(state.amountText) ?: 0L
            state.copy(amountText = minorToInput(current + rubles * 100), error = null)
        }
    }

    fun clearAmount() = _uiState.update { it.copy(amountText = "", error = null) }

    fun selectCategory(id: Long) {
        _uiState.update {
            if (it.categoryId == id) it
            else it.copy(categoryId = id, subcategoryId = null, error = null)
        }
    }

    /** Повторное нажатие по выбранной подкатегории снимает выбор — она необязательна. */
    fun toggleSubcategory(id: Long) {
        _uiState.update { it.copy(subcategoryId = if (it.subcategoryId == id) null else id) }
    }

    fun setDate(date: LocalDate) = _uiState.update { it.copy(date = date) }

    fun setNote(note: String) = _uiState.update { it.copy(note = note) }

    fun setPaymentMethod(method: PaymentMethod) = _uiState.update { it.copy(paymentMethod = method) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    /** Создаёт подкатегорию в текущей категории и сразу её выбирает. */
    fun addSubcategory(name: String) {
        val categoryId = _uiState.value.categoryId ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.addSubcategory(categoryId, name)
            if (id > 0) _uiState.update { it.copy(subcategoryId = id) }
        }
    }

    fun save() {
        val state = _uiState.value
        val amount = state.amountMinor
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(error = "Введите сумму больше нуля") }
            return
        }
        val categoryId = state.categoryId
        if (categoryId == null) {
            _uiState.update { it.copy(error = "Выберите категорию") }
            return
        }
        if (state.saving) return

        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val draft = ExpenseDraft(
                amountMinor = amount,
                categoryId = categoryId,
                subcategoryId = state.subcategoryId,
                date = state.date,
                note = state.note.trim(),
                paymentMethod = state.paymentMethod,
                type = state.type,
            )
            runCatching {
                if (editExpenseId != null) repository.updateExpense(draft.copy(id = editExpenseId))
                else repository.addExpense(draft)
            }.onSuccess {
                _uiState.update { it.copy(saving = false, finished = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(saving = false, error = error.message ?: "Не удалось сохранить трату")
                }
            }
        }
    }

    fun delete() {
        val id = editExpenseId ?: return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.deleteExpense(id) }
                .onSuccess { _uiState.update { it.copy(saving = false, finished = true) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(saving = false, error = error.message ?: "Не удалось удалить трату")
                    }
                }
        }
    }

    private companion object {
        /** Копейки → строка для поля ввода (без пробелов и символа валюты). */
        fun minorToInput(minor: Long): String {
            val rubles = minor / 100
            val kopeks = (minor % 100).toInt()
            return if (kopeks == 0) rubles.toString()
            else "$rubles,${kopeks.toString().padStart(2, '0')}"
        }
    }
}
