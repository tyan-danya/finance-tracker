package com.dtyan.spendtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtyan.spendtracker.domain.model.Period
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Управляемый селектор периода: строка «‹ Название ›» + ряд чипов режимов.
 *
 * Компонент ничего не хранит про выбранный период — он приходит снаружи в [period],
 * а любые изменения уходят через [onPeriodChange].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PeriodSelector(
    period: Period,
    availableMonths: List<YearMonth>,
    onPeriodChange: (Period) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val currentMonth = remember(today) { YearMonth.from(today) }

    // Месяцы для выпадающего списка: те, где есть траты, плюс обязательно текущий.
    val months = remember(availableMonths, currentMonth) {
        (availableMonths + currentMonth).distinct().sortedDescending()
    }
    val years = remember(availableMonths, today) {
        (availableMonths.map { it.year } + today.year).distinct().sortedDescending()
    }

    var monthMenuOpen by remember { mutableStateOf(false) }
    var yearMenuOpen by remember { mutableStateOf(false) }
    var dayPickerOpen by remember { mutableStateOf(false) }
    // Двухшаговый выбор произвольного периода: сначала «с», потом «по».
    var rangeFromPickerOpen by remember { mutableStateOf(false) }
    var rangeToPickerOpen by remember { mutableStateOf(false) }
    var pendingRangeFrom by remember { mutableStateOf<LocalDate?>(null) }

    // Стрелки листают только «шаговые» периоды.
    val steppable = period is Period.Month || period is Period.Day || period is Period.Year
    val previousStep: Period? = when (period) {
        is Period.Month -> Period.Month(period.yearMonth.minusMonths(1))
        is Period.Day -> Period.Day(period.date.minusDays(1))
        is Period.Year -> Period.Year(period.year - 1)
        else -> null
    }
    // Вперёд не пускаем дальше текущего месяца / дня / года.
    val nextStep: Period? = when (period) {
        is Period.Month ->
            if (period.yearMonth < currentMonth) Period.Month(period.yearMonth.plusMonths(1)) else null

        is Period.Day ->
            if (period.date.isBefore(today)) Period.Day(period.date.plusDays(1)) else null

        is Period.Year ->
            if (period.year < today.year) Period.Year(period.year + 1) else null

        else -> null
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // --- строка с названием периода и стрелками ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (steppable) {
                IconButton(
                    onClick = { previousStep?.let(onPeriodChange) },
                    enabled = previousStep != null,
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий период")
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }

            // Клик по названию открывает тот же выбор, что и соответствующий чип.
            TextButton(
                onClick = {
                    when (period) {
                        is Period.Month -> monthMenuOpen = true
                        is Period.Year -> yearMenuOpen = true
                        is Period.Day -> dayPickerOpen = true
                        is Period.Custom -> {
                            pendingRangeFrom = null
                            rangeFromPickerOpen = true
                        }

                        Period.AllTime -> Unit
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = period.title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }

            if (steppable) {
                IconButton(
                    onClick = { nextStep?.let(onPeriodChange) },
                    enabled = nextStep != null,
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий период")
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // --- чипы режимов ---
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Месяц + выпадающий список месяцев
            Box {
                FilterChip(
                    selected = period is Period.Month,
                    onClick = { monthMenuOpen = true },
                    label = { Text("Месяц") },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = monthMenuOpen,
                    onDismissRequest = { monthMenuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    months.forEach { ym ->
                        val item = Period.Month(ym)
                        DropdownMenuItem(
                            text = { Text(item.title) },
                            onClick = {
                                monthMenuOpen = false
                                onPeriodChange(item)
                            },
                        )
                    }
                }
            }

            FilterChip(
                selected = period is Period.Day,
                onClick = { dayPickerOpen = true },
                label = { Text("День") },
            )

            // Год + выпадающий список годов
            Box {
                FilterChip(
                    selected = period is Period.Year,
                    onClick = { yearMenuOpen = true },
                    label = { Text("Год") },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = yearMenuOpen,
                    onDismissRequest = { yearMenuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    years.forEach { year ->
                        DropdownMenuItem(
                            text = { Text("$year год") },
                            onClick = {
                                yearMenuOpen = false
                                onPeriodChange(Period.Year(year))
                            },
                        )
                    }
                }
            }

            FilterChip(
                selected = period is Period.Custom,
                onClick = {
                    pendingRangeFrom = null
                    rangeFromPickerOpen = true
                },
                label = { Text("Период") },
            )

            FilterChip(
                selected = period is Period.AllTime,
                onClick = { onPeriodChange(Period.AllTime) },
                label = { Text("Всё время") },
            )
        }
    }

    // --- диалоги выбора дат ---

    if (dayPickerOpen) {
        val initial = (period as? Period.Day)?.date ?: today
        SingleDatePickerDialog(
            title = "Выберите день",
            initial = initial,
            minDate = null,
            maxDate = today,
            onDismiss = { dayPickerOpen = false },
            onConfirm = { date ->
                dayPickerOpen = false
                onPeriodChange(Period.Day(date))
            },
        )
    }

    if (rangeFromPickerOpen) {
        val initial = (period as? Period.Custom)?.from ?: today.minusDays(6)
        SingleDatePickerDialog(
            title = "Начало периода",
            initial = initial,
            minDate = null,
            maxDate = today,
            onDismiss = { rangeFromPickerOpen = false },
            onConfirm = { date ->
                rangeFromPickerOpen = false
                pendingRangeFrom = date
                rangeToPickerOpen = true
            },
        )
    }

    if (rangeToPickerOpen) {
        val from = pendingRangeFrom ?: today
        val initial = (period as? Period.Custom)?.to?.takeIf { !it.isBefore(from) } ?: today
        SingleDatePickerDialog(
            title = "Конец периода",
            initial = initial,
            minDate = from,
            maxDate = today,
            onDismiss = {
                rangeToPickerOpen = false
                pendingRangeFrom = null
            },
            onConfirm = { date ->
                rangeToPickerOpen = false
                pendingRangeFrom = null
                // На всякий случай нормализуем порядок границ.
                val start = minOf(from, date)
                val end = maxOf(from, date)
                onPeriodChange(Period.Custom(start, end))
            },
        )
    }
}

/**
 * Простой диалог выбора одной даты. Два таких диалога подряд заменяют
 * капризный `DateRangePicker` при выборе произвольного периода.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDatePickerDialog(
    title: String,
    initial: LocalDate,
    minDate: LocalDate?,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val minMillis = minDate?.let { it.toEpochDay() * MILLIS_IN_DAY }
    val maxMillis = maxDate.toEpochDay() * MILLIS_IN_DAY
    val minYear = minDate?.year ?: (maxDate.year - 20)
    val selectableDates = remember(minMillis, maxMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis <= maxMillis && (minMillis == null || utcTimeMillis >= minMillis)

            override fun isSelectableYear(year: Int): Boolean = year in minYear..maxDate.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * MILLIS_IN_DAY,
        yearRange = minYear..maxDate.year,
        selectableDates = selectableDates,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onConfirm(millis.toLocalDateUtc())
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
        )
    }
}

private const val MILLIS_IN_DAY = 24L * 60 * 60 * 1000

/** Material3 отдаёт миллисекунды в UTC — конвертируем строго через [ZoneOffset.UTC]. */
private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
