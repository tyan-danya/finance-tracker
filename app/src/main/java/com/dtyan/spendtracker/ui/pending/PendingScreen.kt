package com.dtyan.spendtracker.ui.pending

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.PendingOperation
import com.dtyan.spendtracker.domain.model.PendingStatus
import com.dtyan.spendtracker.domain.model.Period
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Зелёный акцент для пополнений — как в списке трат и предпросмотре импорта. */
private val IncomeGreen = Color(0xFF2E7D32)

/**
 * «Черновики» — операции, распознанные из банковских уведомлений.
 *
 * Экран намеренно отделён от списка трат: пока пользователь не подтвердил операцию,
 * она не участвует ни в статистике, ни в экспорте. Здесь он за несколько секунд
 * решает по каждой: подтвердить (при необходимости поправив) или отклонить.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(
    repository: ExpenseRepository,
    settings: SettingsStore,
    onOpenSettings: () -> Unit,
) {
    val vm: PendingViewModel = viewModel(
        factory = viewModelFactory { initializer { PendingViewModel(repository, settings) } }
    )
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<PendingOperation?>(null) }
    var showRejectAll by remember { mutableStateOf(false) }

    // Разовые сообщения: подтверждение, отклонение (с возможностью вернуть), ошибки.
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val (text, actionLabel) = when (current) {
            is PendingMessage.Confirmed -> "«${current.title}» добавлена в траты" to null
            is PendingMessage.ConfirmedMany -> "Добавлено операций: ${current.count}" to null
            is PendingMessage.Rejected -> "«${current.title}» отклонена" to "Вернуть"
            is PendingMessage.RejectedMany -> "Отклонено операций: ${current.count}" to null
            PendingMessage.AlreadyExists -> "Такая трата уже была добавлена раньше" to null
            PendingMessage.InvalidAmount -> "Укажите сумму больше нуля" to null
        }
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && current is PendingMessage.Rejected) {
            current.restorable?.let(vm::restore)
        }
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Черновики") },
                actions = {
                    if (state.operations.isNotEmpty()) {
                        IconButton(onClick = { showRejectAll = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Отклонить все")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки автоучёта")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        autoCaptureEnabled = state.autoCaptureEnabled,
                        onOpenSettings = onOpenSettings,
                    )
                }
            } else {
                item(key = "summary") {
                    SummaryCard(
                        state = state,
                        onConfirmAll = vm::confirmAllReady,
                    )
                }

                // Группировка по дню операции: разбирать ленту так привычнее.
                val grouped = state.operations.groupBy { it.date }
                grouped.forEach { (date, operations) ->
                    item(key = "day-$date") { DayHeader(date, operations) }
                    items(operations, key = { it.id }) { operation ->
                        PendingCard(
                            operation = operation,
                            isDuplicate = operation.id in state.duplicateIds,
                            onConfirm = { vm.confirm(operation) },
                            onReject = { vm.reject(operation) },
                            onEdit = { editing = operation },
                        )
                    }
                }
            }
        }
    }

    // Карточка правки: сумма, дата, категория, комментарий — и подтверждение.
    editing?.let { operation ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = sheetState,
        ) {
            PendingEditSheet(
                operation = operation,
                categoriesFor = state::categoriesFor,
                onCategoryChange = { categoryId, subcategoryId ->
                    vm.setCategory(operation.id, categoryId, subcategoryId)
                },
                onConfirm = { draft ->
                    vm.confirm(operation, draft)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { editing = null }
                },
                onReject = {
                    vm.reject(operation)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { editing = null }
                },
            )
        }
    }

    if (showRejectAll) {
        com.dtyan.spendtracker.ui.components.ConfirmDialog(
            title = "Отклонить все черновики?",
            text = "Операций в очереди: ${state.operations.size}. Они будут удалены и в тратах не появятся.",
            confirmText = "Отклонить все",
            onDismiss = { showRejectAll = false },
            onConfirm = {
                showRejectAll = false
                vm.rejectAll()
            },
        )
    }
}

// --- блоки списка ---

@Composable
private fun EmptyState(autoCaptureEnabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (autoCaptureEnabled) "Пока пусто" else "Автоучёт выключен",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (autoCaptureEnabled) {
                    "Как только банк пришлёт уведомление о покупке, операция появится здесь. " +
                        "В траты и статистику она попадёт только после вашего подтверждения."
                } else {
                    "Приложение может само разбирать уведомления банков и складывать траты сюда — " +
                        "останется только подтвердить их. Ничего не добавляется молча, " +
                        "и данные никуда не отправляются: у приложения нет доступа в интернет."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (autoCaptureEnabled) "Настройки автоучёта" else "Включить автоучёт")
            }
        }
    }
}

@Composable
private fun SummaryCard(state: PendingUiState, onConfirmAll: () -> Unit) {
    val total = state.operations.filter { !it.isIncome }.sumOf { it.amountMinor }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Ждут решения: ${state.operations.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "На сумму ${MoneyFormat.format(total)} · в статистику пока не входят",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.readyCount > 0) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onConfirmAll, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Подтвердить готовые (${state.readyCount})")
                }
                Text(
                    text = "Готовы те, где категория уже подобрана. Остальные — ниже, выберите категорию.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, operations: List<PendingOperation>) {
    val today = remember { LocalDate.now() }
    val label = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${operations.size} оп.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingCard(
    operation: PendingOperation,
    isDuplicate: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(operation)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = operation.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(operation.dateTime.toLocalTime().format(TIME_FORMAT))
                            append(" · ")
                            append(operation.bankTitle)
                            operation.cardMask?.let { append(" · •$it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = (if (operation.isIncome) "+" else "") + MoneyFormat.format(operation.amountMinor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (operation.isIncome) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(10.dp))
            CategoryLine(operation, onEdit)

            if (operation.status == PendingStatus.UNPARSED) {
                NoticeLine(
                    text = "Сумму нашли, но тип операции неясен — проверьте текст и поправьте вручную.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = operation.rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (isDuplicate) {
                NoticeLine(
                    text = "Похоже на трату, которая уже есть в приложении",
                    color = MaterialTheme.colorScheme.error,
                    icon = true,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReject) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Отклонить")
                }
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Изменить")
                }
                Button(
                    onClick = onConfirm,
                    enabled = operation.isReadyToConfirm,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("В траты")
                }
            }
        }
    }
}

/** Кружок с эмодзи категории; если категории нет — вопросительный знак. */
@Composable
private fun CategoryBadge(operation: PendingOperation) {
    val color = operation.categoryColorArgb?.let { Color(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = operation.categoryIcon ?: "❓", fontSize = 18.sp)
    }
}

/** Строка с предложенной категорией или предложением её выбрать. */
@Composable
private fun CategoryLine(operation: PendingOperation, onEdit: () -> Unit) {
    if (operation.categoryId == null) {
        OutlinedButton(onClick = onEdit) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Выбрать категорию")
        }
        return
    }
    val label = buildString {
        append(operation.categoryName)
        operation.subcategoryName?.let { append(" / ").append(it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onEdit,
            label = { Text(label) },
            leadingIcon = { Text(operation.categoryIcon.orEmpty(), fontSize = 14.sp) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = operation.categoryColorArgb
                    ?.let { Color(it).copy(alpha = 0.16f) }
                    ?: MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        operation.suggestionSource?.let { source ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoticeLine(text: String, color: Color, icon: Boolean = false) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// --- карточка правки ---

/**
 * Правка операции перед подтверждением. Изначально всё заполнено данными уведомления,
 * поэтому в типичном случае пользователь только выбирает категорию и жмёт «Подтвердить».
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PendingEditSheet(
    operation: PendingOperation,
    categoriesFor: (EntryType) -> List<CategoryTree>,
    onCategoryChange: (Long?, Long?) -> Unit,
    onConfirm: (ExpenseDraft) -> Unit,
    onReject: () -> Unit,
) {
    // Сумма в поле — без разделителей разрядов: её сразу можно править с клавиатуры.
    var amountText by remember(operation.id) {
        mutableStateOf(
            if (operation.amountMinor > 0) {
                val rubles = operation.amountMinor / 100
                val kopeks = (operation.amountMinor % 100).toInt()
                if (kopeks == 0) "$rubles" else "$rubles,${kopeks.toString().padStart(2, '0')}"
            } else {
                ""
            }
        )
    }
    var type by remember(operation.id) { mutableStateOf(operation.type) }
    var date by remember(operation.id) { mutableStateOf(operation.date) }
    var categoryId by remember(operation.id) { mutableStateOf(operation.categoryId) }
    var subcategoryId by remember(operation.id) { mutableStateOf(operation.subcategoryId) }
    var note by remember(operation.id) { mutableStateOf(operation.merchant.orEmpty()) }
    var paymentMethod by remember(operation.id) {
        mutableStateOf(if (operation.isIncome) PaymentMethod.TRANSFER else PaymentMethod.CARD)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    val trees = categoriesFor(type)
    val subcategories = trees.firstOrNull { it.category.id == categoryId }?.subcategories.orEmpty()
    val amountMinor = MoneyFormat.parseToMinor(amountText) ?: 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = operation.displayTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${operation.bankTitle} · ${operation.dateTime.toLocalTime().format(TIME_FORMAT)}" +
                (operation.cardMask?.let { " · карта •$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            val types = EntryType.entries
            types.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = type == entry,
                    onClick = {
                        type = entry
                        // Категории расходов и доходов не пересекаются — сбрасываем выбор.
                        categoryId = null
                        subcategoryId = null
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                ) { Text(entry.title) }
            }
        }

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            singleLine = true,
            label = { Text("Сумма") },
            suffix = { Text("₽") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Дата: ${date.format(Period.DAY_FORMAT)}", style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.weight(1f))
            TextButton(onClick = { showDatePicker = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Изменить")
            }
        }

        HorizontalDivider()

        Text(
            text = "Категория",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            trees.forEach { tree ->
                val category = tree.category
                val selected = category.id == categoryId
                FilterChip(
                    selected = selected,
                    onClick = {
                        categoryId = category.id
                        subcategoryId = null
                        onCategoryChange(category.id, null)
                    },
                    label = { Text(category.name) },
                    leadingIcon = { Text(category.icon, fontSize = 16.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(category.colorArgb).copy(alpha = 0.30f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }

        AnimatedVisibility(visible = subcategories.isNotEmpty()) {
            Column {
                Text(
                    text = "Подкатегория",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    subcategories.forEach { sub ->
                        FilterChip(
                            selected = sub.id == subcategoryId,
                            onClick = {
                                subcategoryId = if (subcategoryId == sub.id) null else sub.id
                                onCategoryChange(categoryId, subcategoryId)
                            },
                            label = { Text(sub.name) },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            singleLine = true,
            label = { Text("Комментарий") },
        )

        Text(
            text = "Способ оплаты",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaymentMethod.entries.forEach { method ->
                FilterChip(
                    selected = method == paymentMethod,
                    onClick = { paymentMethod = method },
                    label = { Text(method.title) },
                )
            }
        }

        TextButton(
            onClick = { showRaw = !showRaw },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (showRaw) "Скрыть текст уведомления" else "Показать текст уведомления")
        }
        AnimatedVisibility(visible = showRaw) {
            Text(
                text = operation.rawText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text("Отклонить")
            }
            Button(
                onClick = {
                    val selected = categoryId ?: return@Button
                    onConfirm(
                        ExpenseDraft(
                            amountMinor = amountMinor,
                            categoryId = selected,
                            subcategoryId = subcategoryId,
                            date = date,
                            note = note.trim(),
                            paymentMethod = paymentMethod,
                            currency = operation.currency,
                            type = type,
                        )
                    )
                },
                enabled = categoryId != null && amountMinor > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text("Подтвердить")
            }
        }
        if (categoryId == null) {
            Text(
                text = "Выберите категорию — без неё трата не попадёт в статистику осмысленно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // DatePicker отдаёт полночь UTC — обратно тоже через UTC, иначе дата «уедет».
                        pickerState.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
