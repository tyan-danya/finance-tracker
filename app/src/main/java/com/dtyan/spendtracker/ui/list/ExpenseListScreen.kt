package com.dtyan.spendtracker.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.ui.components.PeriodSelector
import com.dtyan.spendtracker.ui.theme.ChartPalette
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")

/** Зелёный акцент для пополнений (доходов). В colorScheme подходящего цвета нет. */
private val IncomeGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExpenseListScreen(
    repository: ExpenseRepository,
    onEdit: (Long) -> Unit,
) {
    val vm: ExpenseListViewModel = viewModel(
        factory = viewModelFactory { initializer { ExpenseListViewModel(repository) } }
    )
    val state by vm.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    // Запись, для которой открыт диалог подтверждения удаления.
    var pendingDelete by remember { mutableStateOf<ExpenseRecord?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Записи") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "period") {
                PeriodSelector(
                    period = state.period,
                    availableMonths = state.availableMonths,
                    onPeriodChange = vm::setPeriod,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            item(key = "summary") {
                SummaryCard(
                    totalMinor = state.totalMinor,
                    count = state.count,
                    averageMinor = state.averageMinor,
                    incomeMinor = state.incomeMinor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    label = { Text("Поиск по комментарию и категории") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Очистить поиск")
                            }
                        }
                    },
                )
            }

            if (state.categories.isNotEmpty()) {
                item(key = "categories") {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.categoryFilter == null,
                                onClick = { vm.setCategoryFilter(null) },
                                label = { Text("Все") },
                            )
                        }
                        items(state.categories, key = { it.id }) { category ->
                            FilterChip(
                                selected = state.categoryFilter == category.id,
                                onClick = {
                                    vm.setCategoryFilter(
                                        if (state.categoryFilter == category.id) null else category.id
                                    )
                                },
                                label = { Text("${category.name} · ${category.count}") },
                            )
                        }
                    }
                }
            }

            if (state.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (state.hasAnyInPeriod) {
                            "Ничего не найдено"
                        } else {
                            "Пока нет трат за этот период"
                        },
                        subtitle = if (state.hasAnyInPeriod) {
                            "Попробуйте изменить поиск или снять фильтр по категории."
                        } else {
                            "Добавьте первую трату — или выберите другой период выше."
                        },
                    )
                }
            } else {
                state.groups.forEach { group ->
                    item(key = "header-${group.date}") {
                        DayHeader(
                            date = group.date,
                            today = today,
                            totalMinor = group.totalMinor,
                        )
                    }
                    items(group.records, key = { it.id }) { record ->
                        ExpenseRow(
                            record = record,
                            onClick = { onEdit(record.id) },
                            onLongClick = { pendingDelete = record },
                            onDeleteClick = { pendingDelete = record },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить трату?") },
            text = {
                Text(
                    "${MoneyFormat.format(toDelete.amountMinor)} · ${toDelete.categoryName}" +
                        if (toDelete.note.isBlank()) "" else "\n${toDelete.note}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        vm.delete(toDelete.id)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Трата удалена",
                                actionLabel = "Отменить",
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                        }
                    }
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SummaryCard(
    totalMinor: Long,
    count: Int,
    averageMinor: Long,
    incomeMinor: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = MoneyFormat.format(totalMinor),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${plural(count, "трата", "траты", "трат")} · средний чек ${MoneyFormat.format(averageMinor)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            // Пополнения показываем отдельной строкой, только если они были в периоде.
            if (incomeMinor > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Пополнения: +${MoneyFormat.format(incomeMinor)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = IncomeGreen,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
    totalMinor: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dayTitle(date, today),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormat.format(totalMinor),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpenseRow(
    record: ExpenseRecord,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // В ExpenseRecord нет иконки и цвета — цвет выводим детерминированно из id категории.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(categoryColor(record.categoryId).copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = record.categoryName.take(1).uppercase(RU),
                style = MaterialTheme.typography.titleMedium,
                color = categoryColor(record.categoryId),
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(record.categoryName)
                    record.subcategoryName?.let { append(" / ").append(it) }
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = record.note.ifBlank { record.paymentMethod.title }
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        // Пополнения выделяем знаком «+» и зелёным цветом; расходы — как обычно.
        Text(
            text = (if (record.isIncome) "+" else "") + MoneyFormat.format(record.amountMinor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (record.isIncome) IncomeGreen else Color.Unspecified,
        )

        IconButton(onClick = onDeleteClick) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Удалить",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- вспомогательные функции ---

internal fun categoryColor(categoryId: Long) =
    ChartPalette[categoryId.toInt().mod(ChartPalette.size)]

/** «Сегодня, понедельник» / «Вчера, воскресенье» / «12.05.2026, вторник». */
private fun dayTitle(date: LocalDate, today: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, RU)
    val head = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date.format(com.dtyan.spendtracker.domain.model.Period.DAY_FORMAT)
    }
    return "$head, $weekday"
}

/** Русские склонения для счётчика трат. */
internal fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}
