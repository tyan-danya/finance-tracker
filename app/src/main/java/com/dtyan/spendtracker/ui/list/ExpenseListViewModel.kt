package com.dtyan.spendtracker.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.Period
import com.dtyan.spendtracker.domain.stats.StatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Категория, встретившаяся в текущем периоде, — для ряда чипов-фильтров. */
data class CategoryChipItem(
    val id: Long,
    val name: String,
    val count: Int,
)

/** Группа записей за один день. */
data class DayGroup(
    val date: LocalDate,
    val totalMinor: Long,
    val records: List<ExpenseRecord>,
)

data class ExpenseListUiState(
    val period: Period = Period.currentMonth(LocalDate.now()),
    val query: String = "",
    val categoryFilter: Long? = null,
    val availableMonths: List<YearMonth> = emptyList(),
    val categories: List<CategoryChipItem> = emptyList(),
    val groups: List<DayGroup> = emptyList(),
    /** Итог по видимым (отфильтрованным) записям — только расходы. */
    val totalMinor: Long = 0L,
    val count: Int = 0,
    val averageMinor: Long = 0L,
    /** Сумма пополнений среди видимых записей (для отдельной строки в сводке). */
    val incomeMinor: Long = 0L,
    /** Есть ли вообще траты в периоде — чтобы отличить «пусто» от «ничего не найдено». */
    val hasAnyInPeriod: Boolean = false,
)

/**
 * Состояние экрана списка трат: период, поиск, фильтр по категории.
 * Всё производное считается в одном [combine] поверх потока записей.
 */
class ExpenseListViewModel(
    private val repository: ExpenseRepository,
) : ViewModel() {

    private val _period = MutableStateFlow<Period>(Period.currentMonth(LocalDate.now()))
    val period: StateFlow<Period> = _period.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _categoryFilter = MutableStateFlow<Long?>(null)
    val categoryFilter: StateFlow<Long?> = _categoryFilter.asStateFlow()

    /** Последняя удалённая запись — для «Отменить» в снекбаре. */
    private var lastDeleted: ExpenseRecord? = null

    val state: StateFlow<ExpenseListUiState> = combine(
        repository.observeExpenses(),
        _period,
        _query,
        _categoryFilter,
    ) { all, period, query, categoryFilter ->
        buildState(all, period, query, categoryFilter)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExpenseListUiState(period = _period.value),
        )

    fun setPeriod(value: Period) {
        _period.value = value
        // Категория из прошлого периода может там не встречаться — сбрасываем фильтр.
        _categoryFilter.value = null
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCategoryFilter(value: Long?) {
        _categoryFilter.value = value
    }

    fun delete(id: Long) {
        lastDeleted = state.value.groups
            .asSequence()
            .flatMap { it.records.asSequence() }
            .firstOrNull { it.id == id }
        viewModelScope.launch { repository.deleteExpense(id) }
    }

    /** Восстанавливает последнюю удалённую запись (id будет новым — это нормально). */
    fun undoDelete() {
        val record = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            repository.addExpense(
                ExpenseDraft(
                    amountMinor = record.amountMinor,
                    categoryId = record.categoryId,
                    subcategoryId = record.subcategoryId,
                    date = record.date,
                    note = record.note,
                    paymentMethod = record.paymentMethod,
                    currency = record.currency,
                )
            )
        }
    }

    private fun buildState(
        all: List<ExpenseRecord>,
        period: Period,
        query: String,
        categoryFilter: Long?,
    ): ExpenseListUiState {
        val months = StatsCalculator.availableMonths(all)
        val range = period.range(StatsCalculator.dataBounds(all))

        val inPeriod = if (range == null) emptyList() else all.filter { it.date in range }

        // Чипы категорий строим по всему периоду, а не по результату поиска,
        // иначе они прыгали бы при вводе текста.
        val categories = inPeriod
            .groupBy { it.categoryId }
            .map { (id, records) ->
                CategoryChipItem(
                    id = id,
                    name = records.first().categoryName,
                    count = records.size,
                )
            }
            .sortedByDescending { it.count }

        val needle = query.trim().lowercase()
        val visible = inPeriod
            .filter { categoryFilter == null || it.categoryId == categoryFilter }
            .filter { needle.isEmpty() || it.matches(needle) }

        val groups = visible
            .groupBy { it.date }
            .map { (date, records) ->
                DayGroup(
                    date = date,
                    // Итог дня — только по расходам, чтобы пополнения не завышали сумму.
                    totalMinor = records
                        .filter { it.type == EntryType.EXPENSE }
                        .sumOf { it.amountMinor },
                    records = records.sortedWith(
                        compareByDescending<ExpenseRecord> { it.createdAt }.thenByDescending { it.id }
                    ),
                )
            }
            .sortedByDescending { it.date }

        // Сумма/средний чек/количество — строго по расходам; пополнения считаем отдельно.
        val visibleExpenses = visible.filter { it.type == EntryType.EXPENSE }
        val total = visibleExpenses.sumOf { it.amountMinor }
        val income = visible.filter { it.type == EntryType.INCOME }.sumOf { it.amountMinor }
        return ExpenseListUiState(
            period = period,
            query = query,
            categoryFilter = if (categories.any { it.id == categoryFilter }) categoryFilter else null,
            availableMonths = months,
            categories = categories,
            groups = groups,
            totalMinor = total,
            count = visibleExpenses.size,
            averageMinor = if (visibleExpenses.isEmpty()) 0L else total / visibleExpenses.size,
            incomeMinor = income,
            hasAnyInPeriod = inPeriod.isNotEmpty(),
        )
    }
}

/** Регистронезависимый поиск по комментарию, категории и подкатегории. */
private fun ExpenseRecord.matches(needle: String): Boolean =
    note.lowercase().contains(needle) ||
        categoryName.lowercase().contains(needle) ||
        (subcategoryName?.lowercase()?.contains(needle) == true)
