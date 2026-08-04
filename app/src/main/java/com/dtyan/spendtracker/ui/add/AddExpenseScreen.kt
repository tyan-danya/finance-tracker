package com.dtyan.spendtracker.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.Period
import com.dtyan.spendtracker.ui.components.ConfirmDialog
import com.dtyan.spendtracker.ui.components.TextInputDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Экран создания и редактирования траты.
 * Главный сценарий приложения, поэтому всё в один экран без вложенной навигации:
 * сумма -> категория -> подкатегория -> дата -> способ оплаты -> комментарий -> «Сохранить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    repository: ExpenseRepository,
    editExpenseId: Long?,
    onDone: () -> Unit,
    onManageCategories: () -> Unit,
) {
    val vm: AddExpenseViewModel = viewModel(
        key = "add-$editExpenseId",
        factory = viewModelFactory { initializer { AddExpenseViewModel(repository, editExpenseId) } }
    )
    val state by vm.uiState.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewSubcategory by remember { mutableStateOf(false) }

    val amountFocus = remember { FocusRequester() }

    // Закрываем экран после успешного сохранения/удаления.
    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    // Автофокус на сумме только при создании новой траты — при редактировании он мешает.
    LaunchedEffect(state.loaded, state.isEdit) {
        if (state.loaded && !state.isEdit) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.isEdit -> "Редактирование"
                            state.isIncome -> "Новое пополнение"
                            else -> "Новая трата"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.isEdit) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Удалить трату",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = { vm.save() },
                        enabled = !state.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                text = if (state.isEdit) "Сохранить изменения" else "Сохранить",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // Переключатель типа операции: расход или пополнение.
            EntryTypeToggle(
                selected = state.type,
                onSelect = vm::setType,
            )

            AmountBlock(
                amountText = state.amountText,
                onAmountChange = vm::setAmount,
                onQuickAdd = vm::addRubles,
                focusRequester = amountFocus,
                // Для пополнений подсвечиваем сумму зелёным акцентом.
                accentColor = if (state.isIncome) MaterialTheme.colorScheme.primary else null,
            )

            SectionTitle("Категория")
            CategoryChips(
                trees = state.tree,
                selectedId = state.categoryId,
                onSelect = vm::selectCategory,
            )
            TextButton(onClick = onManageCategories) {
                Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Управление категориями")
            }

            if (state.categoryId != null) {
                SectionTitle("Подкатегория")
                SubcategoryChips(
                    subcategories = state.subcategories.map { it.id to it.name },
                    selectedId = state.subcategoryId,
                    onSelect = vm::toggleSubcategory,
                    onAddNew = { showNewSubcategory = true },
                )
            }

            SectionTitle("Дата")
            DateBlock(
                date = state.date,
                onPick = { showDatePicker = true },
                onSelect = vm::setDate,
            )

            SectionTitle("Способ оплаты")
            PaymentMethodChips(
                selected = state.paymentMethod,
                onSelect = vm::setPaymentMethod,
            )

            SectionTitle("Комментарий")
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Необязательно") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )
        }
    }

    if (showDatePicker) {
        ExpenseDatePickerDialog(
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onConfirm = { picked ->
                vm.setDate(picked)
                showDatePicker = false
            },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Удалить трату?",
            text = "Запись будет удалена без возможности восстановления.",
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                vm.delete()
            },
        )
    }

    if (showNewSubcategory) {
        TextInputDialog(
            title = "Новая подкатегория",
            label = "Название",
            onDismiss = { showNewSubcategory = false },
            onConfirm = { name ->
                vm.addSubcategory(name)
                showNewSubcategory = false
            },
        )
    }
}

// --- блоки экрана ---

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** Переключатель «Расход / Пополнение» вверху экрана. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryTypeToggle(
    selected: EntryType,
    onSelect: (EntryType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        val types = EntryType.entries
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
            ) {
                Text(type.title)
            }
        }
    }
}

/** Крупное поле суммы + чипы быстрой прибавки. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AmountBlock(
    amountText: String,
    onAmountChange: (String) -> Unit,
    onQuickAdd: (Long) -> Unit,
    focusRequester: FocusRequester,
    accentColor: Color? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = amountText,
            onValueChange = onAmountChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            placeholder = {
                Text(
                    text = "0",
                    fontSize = 34.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            suffix = { Text("₽", fontSize = 24.sp) },
            textStyle = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                color = accentColor ?: Color.Unspecified,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
        )
        Text(
            text = MoneyFormat.format(MoneyFormat.parseToMinor(amountText) ?: 0),
            style = MaterialTheme.typography.bodySmall,
            color = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100L, 500L, 1000L).forEach { step ->
                FilterChip(
                    selected = false,
                    onClick = { onQuickAdd(step) },
                    label = { Text("+$step") },
                )
            }
        }
    }
}

/** Сетка категорий: эмодзи + название, цвет чипа берётся из самой категории. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    trees: List<CategoryTree>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    if (trees.isEmpty()) {
        Text(
            text = "Категорий пока нет — добавьте их в разделе «Категории»",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        trees.forEach { tree ->
            val category = tree.category
            val selected = category.id == selectedId
            FilterChip(
                selected = selected,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
                leadingIcon = { Text(category.icon, fontSize = 16.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(category.colorArgb).copy(alpha = 0.30f),
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = Color(category.colorArgb).copy(alpha = 0.5f),
                    selectedBorderColor = Color(category.colorArgb),
                    borderWidth = 1.dp,
                    selectedBorderWidth = 2.dp,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubcategoryChips(
    subcategories: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onAddNew: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        subcategories.forEach { (id, name) ->
            FilterChip(
                selected = id == selectedId,
                onClick = { onSelect(id) },
                label = { Text(name) },
            )
        }
        FilterChip(
            selected = false,
            onClick = onAddNew,
            label = { Text("Добавить") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

/** Дата: текущее значение + быстрые чипы «Сегодня / Вчера / Позавчера». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateBlock(
    date: LocalDate,
    onPick: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = date.format(Period.DAY_FORMAT),
                style = MaterialTheme.typography.titleMedium,
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = onPick) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Выбрать")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Сегодня" to today,
                "Вчера" to today.minusDays(1),
                "Позавчера" to today.minusDays(2),
            ).forEach { (label, value) ->
                FilterChip(
                    selected = date == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentMethodChips(
    selected: PaymentMethod,
    onSelect: (PaymentMethod) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PaymentMethod.entries.forEach { method ->
            FilterChip(
                selected = method == selected,
                onClick = { onSelect(method) },
                label = { Text(method.title) },
            )
        }
    }
}

/**
 * Material3 DatePicker. Конвертация обязательно через UTC: DatePicker отдаёт полночь UTC,
 * и при пересчёте в локальную зону дата «уезжает» на сутки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDatePickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } else {
                        onDismiss()
                    }
                }
            ) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = pickerState)
    }
}
