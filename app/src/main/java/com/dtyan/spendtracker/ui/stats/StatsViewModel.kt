package com.dtyan.spendtracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.model.Period
import com.dtyan.spendtracker.domain.stats.Comparison
import com.dtyan.spendtracker.domain.stats.PeriodStats
import com.dtyan.spendtracker.domain.stats.StatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class StatsUiState(
    val period: Period,
    val stats: PeriodStats,
    val comparison: Comparison,
    val availableMonths: List<YearMonth>,
)

/**
 * Считает статистику за выбранный период. Все агрегаты — в [StatsCalculator],
 * вью-модель лишь связывает поток записей с выбранным периодом.
 */
class StatsViewModel(
    repository: ExpenseRepository,
) : ViewModel() {

    private val _period = MutableStateFlow<Period>(Period.currentMonth(LocalDate.now()))
    val period: StateFlow<Period> = _period

    val state: StateFlow<StatsUiState> = combine(
        repository.observeExpenses(),
        _period,
    ) { all, period ->
        StatsUiState(
            period = period,
            stats = StatsCalculator.compute(all, period),
            comparison = StatsCalculator.compare(all, period),
            availableMonths = StatsCalculator.availableMonths(all),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyState(_period.value),
        )

    fun setPeriod(value: Period) {
        _period.value = value
    }

    private companion object {
        /** Пустая заготовка до первой эмиссии из базы. */
        fun emptyState(period: Period) = StatsUiState(
            period = period,
            stats = StatsCalculator.compute(emptyList(), period),
            comparison = Comparison(currentMinor = 0L, previousMinor = 0L),
            availableMonths = emptyList(),
        )
    }
}
