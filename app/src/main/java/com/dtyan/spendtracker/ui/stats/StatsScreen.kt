package com.dtyan.spendtracker.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.Period
import com.dtyan.spendtracker.domain.stats.CategoryStat
import com.dtyan.spendtracker.domain.stats.Comparison
import com.dtyan.spendtracker.domain.stats.PeriodStats
import com.dtyan.spendtracker.ui.components.BarChart
import com.dtyan.spendtracker.ui.components.ChartBar
import com.dtyan.spendtracker.ui.components.ChartLegend
import com.dtyan.spendtracker.ui.components.ChartLinePoint
import com.dtyan.spendtracker.ui.components.ChartSlice
import com.dtyan.spendtracker.ui.components.DonutChart
import com.dtyan.spendtracker.ui.components.HorizontalBarList
import com.dtyan.spendtracker.ui.components.LineChart
import com.dtyan.spendtracker.ui.components.PeriodSelector
import com.dtyan.spendtracker.ui.theme.ChartPalette
import java.time.DayOfWeek

/** Зелёный «хорошо» — трат стало меньше. В colorScheme подходящего цвета нет. */
private val GoodGreen = Color(0xFF2E7D32)

/** Сколько категорий показываем отдельными секторами кольца, остальные — «Прочее». */
private const val DONUT_SLICES = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(repository: ExpenseRepository) {
    val vm: StatsViewModel = viewModel(
        factory = viewModelFactory { initializer { StatsViewModel(repository) } }
    )
    val state by vm.state.collectAsState()
    val stats = state.stats

    // Индекс выделенного сектора кольца (в пределах видимых слайсов).
    var selectedSlice by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Статистика") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "period") {
                PeriodSelector(
                    period = state.period,
                    availableMonths = state.availableMonths,
                    onPeriodChange = {
                        selectedSlice = null
                        vm.setPeriod(it)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (stats.isEmpty) {
                item(key = "empty") { EmptyStatsCard() }
                return@LazyColumn
            }

            // 2. Итог + сравнение с прошлым периодом
            item(key = "total") {
                TotalCard(stats = stats, comparison = state.comparison, period = state.period)
            }

            // 3. Человекочитаемая сводка
            item(key = "summary") { WhereMoneyGoesCard(stats) }

            // 4. Кольцо по категориям
            item(key = "donut") {
                CategoriesDonutCard(
                    stats = stats,
                    selectedSlice = selectedSlice,
                    onSliceClick = { index ->
                        selectedSlice = if (selectedSlice == index) null else index
                    },
                )
            }

            // 5. Рейтинг категорий
            item(key = "categories") {
                SectionCard(title = "Рейтинг категорий") {
                    HorizontalBarList(
                        items = stats.byCategory.mapIndexed { index, stat ->
                            ChartBar(
                                label = "${stat.categoryName} · ${MoneyFormat.formatPercent(stat.share)}",
                                value = stat.totalMinor,
                                color = paletteColor(index),
                            )
                        },
                    )
                }
            }

            // 6. Динамика
            val days = stats.range?.days ?: 0
            val showDaily = days <= 62 && stats.dailySeries.size >= 2
            val showMonthly = !showDaily && stats.monthlySeries.size >= 2
            if (showDaily) {
                item(key = "daily") {
                    SectionCard(title = "По дням") {
                        LineChart(
                            points = stats.dailySeries.map { ChartLinePoint(it.label, it.totalMinor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                }
            } else if (showMonthly) {
                item(key = "monthly") {
                    SectionCard(title = "По месяцам") {
                        BarChart(
                            bars = stats.monthlySeries.mapIndexed { index, point ->
                                ChartBar(point.label, point.totalMinor, paletteColor(index))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                }
            }

            // 7. Топ подкатегорий
            if (stats.bySubcategory.isNotEmpty()) {
                item(key = "subcategories") {
                    SectionCard(title = "Топ подкатегорий") {
                        HorizontalBarList(
                            items = stats.bySubcategory.take(10).mapIndexed { index, stat ->
                                ChartBar(
                                    label = "${stat.subcategoryName} · ${stat.categoryName}",
                                    value = stat.totalMinor,
                                    color = paletteColor(index),
                                )
                            },
                        )
                    }
                }
            }

            // 8. По дням недели
            if (stats.byWeekday.any { it.totalMinor > 0 }) {
                item(key = "weekday") {
                    SectionCard(title = "По дням недели") {
                        BarChart(
                            bars = stats.byWeekday.map { stat ->
                                ChartBar(
                                    label = stat.dayOfWeek.shortRu(),
                                    value = stat.totalMinor,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        )
                    }
                }
            }

            // 9. Способы оплаты
            if (stats.byPaymentMethod.isNotEmpty()) {
                item(key = "payments") {
                    SectionCard(title = "Способы оплаты") {
                        HorizontalBarList(
                            items = stats.byPaymentMethod.mapIndexed { index, stat ->
                                ChartBar(
                                    label = "${stat.method.title} · ${MoneyFormat.formatPercent(stat.share)}",
                                    value = stat.totalMinor,
                                    color = paletteColor(index),
                                )
                            },
                        )
                    }
                }
            }

            // 10. Самая крупная трата
            stats.maxSingle?.let { record ->
                item(key = "max") {
                    SectionCard(title = "Самая крупная трата") {
                        Text(
                            text = MoneyFormat.format(record.amountMinor),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append(record.categoryName)
                                record.subcategoryName?.let { append(" / ").append(it) }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = record.date.format(Period.DAY_FORMAT) +
                                " · " + record.paymentMethod.title +
                                if (record.note.isBlank()) "" else " · ${record.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- карточки ---

@Composable
private fun TotalCard(stats: PeriodStats, comparison: Comparison, period: Period) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Всего за период",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = MoneyFormat.format(stats.totalMinor),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))
            ComparisonRow(comparison = comparison, period = period)

            // Пополнения и баланс показываем только если за период были доходы.
            if (stats.incomeMinor > 0) {
                Spacer(Modifier.height(12.dp))
                MetricRow(
                    title = "Пополнения",
                    value = "+" + MoneyFormat.format(stats.incomeMinor),
                    valueColor = GoodGreen,
                )
                MetricRow(
                    title = "Баланс",
                    value = MoneyFormat.format(stats.balanceMinor),
                    valueColor = if (stats.balanceMinor >= 0) GoodGreen else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))

            MetricRow("Количество трат", stats.count.toString())
            MetricRow("Средний чек", MoneyFormat.format(stats.averagePerTransactionMinor))
            MetricRow("В среднем в день", MoneyFormat.format(stats.averagePerDayMinor))
            MetricRow("Медиана", MoneyFormat.format(stats.medianTransactionMinor))
        }
    }
}

@Composable
private fun ComparisonRow(comparison: Comparison, period: Period) {
    val ratio = comparison.deltaRatio
    if (ratio == null) {
        Text(
            text = "Нет данных за прошлый период",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        return
    }

    val grew = comparison.deltaMinor > 0
    val same = comparison.deltaMinor == 0L
    val color = when {
        same -> MaterialTheme.colorScheme.onPrimaryContainer
        grew -> MaterialTheme.colorScheme.error
        else -> GoodGreen
    }
    val arrow = if (grew) "↑" else "↓"
    val sign = if (grew) "+" else "−"
    val deltaAbs = if (comparison.deltaMinor < 0) -comparison.deltaMinor else comparison.deltaMinor
    val ratioAbs = if (ratio < 0) -ratio else ratio

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!same) {
            Icon(
                imageVector = if (grew) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = if (same) {
                "Столько же, сколько ${previousLabel(period)}"
            } else {
                "$arrow ${MoneyFormat.formatPercent(ratioAbs)} к ${previousLabel(period)} " +
                    "($sign${MoneyFormat.format(deltaAbs)})"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun MetricRow(title: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

@Composable
private fun WhereMoneyGoesCard(stats: PeriodStats) {
    val top = stats.byCategory.take(3)
    if (top.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Куда уходят деньги",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            val first = top[0]
            Text(
                text = "Больше всего — на «${first.categoryName}»: " +
                    "${MoneyFormat.format(first.totalMinor)} " +
                    "(${MoneyFormat.formatPercent(first.share)} от всех трат).",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (top.size > 1) {
                Spacer(Modifier.height(6.dp))
                val rest = top.drop(1).joinToString(", ") {
                    "«${it.categoryName}» — ${MoneyFormat.format(it.totalMinor)} " +
                        "(${MoneyFormat.formatPercent(it.share)})"
                }
                Text(
                    text = "Далее: $rest.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val topShare = top.sumOf { it.share }
            if (top.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "На эти категории приходится ${MoneyFormat.formatPercent(topShare)} расходов.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CategoriesDonutCard(
    stats: PeriodStats,
    selectedSlice: Int?,
    onSliceClick: (Int) -> Unit,
) {
    val visible: List<CategoryStat> = stats.byCategory.take(DONUT_SLICES)
    val restTotal = stats.byCategory.drop(DONUT_SLICES).sumOf { it.totalMinor }

    val slices = buildList {
        visible.forEachIndexed { index, stat ->
            add(ChartSlice(stat.categoryName, stat.totalMinor, paletteColor(index)))
        }
        if (restTotal > 0) {
            add(ChartSlice("Прочее", restTotal, paletteColor(DONUT_SLICES)))
        }
    }

    // «Прочее» разворачивать нечего — считаем выделенной только реальную категорию.
    val selectedStat = selectedSlice?.let { visible.getOrNull(it) }

    SectionCard(title = "По категориям") {
        DonutChart(
            slices = slices,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            centerTitle = if (selectedStat != null) selectedStat.categoryName else "Всего",
            centerValue = MoneyFormat.format(
                selectedStat?.totalMinor ?: stats.totalMinor
            ),
            selectedIndex = selectedSlice,
            onSliceClick = onSliceClick,
        )
        Spacer(Modifier.height(12.dp))
        ChartLegend(slices = slices)

        if (selectedStat != null) {
            val subs = stats.bySubcategory.filter { it.categoryId == selectedStat.categoryId }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "«${selectedStat.categoryName}» — подкатегории",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (subs.isEmpty()) {
                Text(
                    text = "Подкатегории не указаны",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                subs.forEach { sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    paletteColor(
                                        stats.byCategory.indexOfFirst {
                                            it.categoryId == selectedStat.categoryId
                                        }.coerceAtLeast(0)
                                    )
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = sub.subcategoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = MoneyFormat.format(sub.totalMinor),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyStatsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "За этот период трат нет",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Добавьте траты или выберите другой период — и здесь появятся графики.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- утилиты ---

private fun paletteColor(index: Int): Color = ChartPalette[index.mod(ChartPalette.size)]

private fun DayOfWeek.shortRu(): String = when (this) {
    DayOfWeek.MONDAY -> "Пн"
    DayOfWeek.TUESDAY -> "Вт"
    DayOfWeek.WEDNESDAY -> "Ср"
    DayOfWeek.THURSDAY -> "Чт"
    DayOfWeek.FRIDAY -> "Пт"
    DayOfWeek.SATURDAY -> "Сб"
    DayOfWeek.SUNDAY -> "Вс"
}

/** «прошлому месяцу» / «прошлому дню» / «прошлому году» / «прошлому периоду». */
private fun previousLabel(period: Period): String = when (period) {
    is Period.Month -> "прошлому месяцу"
    is Period.Day -> "прошлому дню"
    is Period.Year -> "прошлому году"
    is Period.Custom -> "предыдущему такому же периоду"
    Period.AllTime -> "прошлому периоду"
}
