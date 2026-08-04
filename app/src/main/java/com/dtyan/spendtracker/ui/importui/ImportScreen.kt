package com.dtyan.spendtracker.ui.importui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.DuplicateVerdict
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.importer.OperationKind
import com.dtyan.spendtracker.importer.ParseResult
import com.dtyan.spendtracker.importer.ParsedOperation
import java.time.format.DateTimeFormatter

/** Зелёный для сумм-поступлений: в colorScheme отдельного «дохода» нет, а он различим в обеих темах. */
private val IncomeGreen = Color(0xFF2E7D32)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(repository: ExpenseRepository) {
    val vm: ImportViewModel = viewModel(
        factory = viewModelFactory { initializer { ImportViewModel(repository) } }
    )
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Показ разовых сообщений вью-модели (например, «Импорт отменён»).
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // Выбор файла. MIME не сужаем: CSV часто отдаётся как text/comma-separated-values
    // или application/octet-stream, поэтому берём "*/*".
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryDisplayName(context, uri)
            vm.onFilePicked(fileName = fileName) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Импорт из банка") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            is ImportUiState.Idle -> IdleContent(
                modifier = Modifier.padding(innerPadding),
                onPick = { picker.launch("*/*") },
            )

            is ImportUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))

            is ImportUiState.Parsed -> ParsedContent(
                modifier = Modifier.padding(innerPadding),
                state = s,
                onToggleGroup = vm::setGroupSelected,
                onToggleOperation = vm::toggleOperation,
                onImport = vm::runImport,
            )

            is ImportUiState.Done -> DoneContent(
                modifier = Modifier.padding(innerPadding),
                state = s,
                onUndo = vm::undoImport,
                onImportMore = vm::reset,
            )

            is ImportUiState.Error -> ErrorContent(
                modifier = Modifier.padding(innerPadding),
                message = s.message,
                onRetry = { picker.launch("*/*") },
                onBack = vm::reset,
            )
        }
    }
}

// --- Idle ---

@Composable
private fun IdleContent(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Импорт выписки Т-Банка",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Выгрузите выписку в Т-Банке (веб-версия → счёт → «Выгрузка операций» → " +
                        "CSV) и выберите файл здесь. Переводы между своими счетами и доходы можно " +
                        "исключить перед импортом. Повторный импорт того же периода не создаёт дубликатов.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выбрать файл выписки")
        }
    }
}

// --- Loading ---

@Composable
private fun LoadingContent(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Читаем и разбираем файл…", style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Parsed (предпросмотр) ---

@Composable
private fun ParsedContent(
    modifier: Modifier,
    state: ImportUiState.Parsed,
    onToggleGroup: (OperationKind, Boolean) -> Unit,
    onToggleOperation: (Int) -> Unit,
    onImport: () -> Unit,
) {
    val ops = state.result.operations
    // Группируем индексы операций по типу, порядок групп — по объявлению enum.
    val groups: List<Pair<OperationKind, List<Int>>> = remember(ops) {
        ops.indices
            .groupBy { ops[it].kind }
            .toList()
            .sortedBy { it.first.ordinal }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SummaryHeader(
                    total = ops.size,
                    selected = state.selectedCount,
                    suspected = state.suspectedCount,
                    alreadyImported = state.alreadyImportedCount,
                    warnings = state.result.warnings,
                )
            }

            groups.forEach { (kind, indices) ->
                val allSelected = indices.all { it in state.selected }
                val anySelected = indices.any { it in state.selected }
                item(key = "group-${kind.name}") {
                    GroupHeader(
                        kind = kind,
                        count = indices.size,
                        checked = anySelected,
                        allSelected = allSelected,
                        onToggle = { include -> onToggleGroup(kind, include) },
                    )
                }
                items(indices, key = { "op-$it" }) { index ->
                    OperationRow(
                        op = ops[index],
                        checked = index in state.selected,
                        verdict = state.verdictAt(index),
                        onToggle = { onToggleOperation(index) },
                    )
                }
            }
        }

        // Кнопка импорта закреплена снизу.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Button(
                onClick = onImport,
                enabled = state.selectedCount > 0 && !state.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Импортировать ${state.selectedCount} операций")
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    total: Int,
    selected: Int,
    suspected: Int,
    alreadyImported: Int,
    warnings: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Разобрано операций: $total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Будет импортировано: $selected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Исключено: ${total - selected}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suspected > 0) {
                Text(
                    text = "Возможные дубликаты: $suspected — уже похожи на записи в приложении, " +
                        "отметьте вручную, если это отдельные операции.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (alreadyImported > 0) {
                Text(
                    text = "Уже импортировано ранее: $alreadyImported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                var expanded by rememberSaveable { mutableStateOf(false) }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        if (expanded) "Скрыть предупреждения (${warnings.size})"
                        else "Показать предупреждения (${warnings.size})"
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        warnings.forEach { w ->
                            Text(
                                text = "• $w",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    kind: OperationKind,
    count: Int,
    checked: Boolean,
    allSelected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = kind.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (checked && !allSelected) "$count оп. · выбрано частично" else "$count оп.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun OperationRow(
    op: ParsedOperation,
    checked: Boolean,
    verdict: DuplicateVerdict,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = op.merchant.ifBlank { "Без описания" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = op.date.format(DATE_FORMAT) + " · " + categoryLabel(op),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Метка возможного/повторного дубликата.
            when (verdict) {
                DuplicateVerdict.SUSPECTED -> Text(
                    text = "⚠ возможный дубликат — уже есть в приложении",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                DuplicateVerdict.ALREADY_IMPORTED -> Text(
                    text = "уже импортировано ранее",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DuplicateVerdict.NONE -> Unit
            }
        }
        Spacer(Modifier.width(8.dp))
        // Доход — со знаком «+» и зелёным; расход — как есть.
        val isIncome = op.kind.isIncome
        Text(
            text = (if (isIncome) "+" else "") + MoneyFormat.format(op.amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isIncome) IncomeGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Предложенная категория для строки предпросмотра. */
private fun categoryLabel(op: ParsedOperation): String {
    if (op.kind.isIncome) return "Пополнения"
    val cat = op.suggestedCategoryName
    if (cat != null) {
        val sub = op.suggestedSubcategoryName
        return if (!sub.isNullOrBlank()) "$cat / $sub" else cat
    }
    return op.bankCategory.ifBlank { "—" }
}

// --- Done ---

@Composable
private fun DoneContent(
    modifier: Modifier,
    state: ImportUiState.Done,
    onUndo: () -> Unit,
    onImportMore: () -> Unit,
) {
    val summary = state.summary
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Импорт завершён",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Добавлено: ${summary.imported}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Дубликатов пропущено: ${summary.duplicates} " +
                        "(из них в базе ${summary.duplicatesInDb})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (summary.batchId != null) {
            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Отменить импорт")
            }
        }

        Button(
            onClick = onImportMore,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Импортировать ещё")
        }
    }
}

// --- Error ---

@Composable
private fun ErrorContent(
    modifier: Modifier,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выбрать другой файл")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Назад")
        }
    }
}

// --- утилиты ---

/**
 * Достаёт человекочитаемое имя файла из [uri]. Сначала пробуем OpenableColumns.DISPLAY_NAME,
 * при неудаче — lastPathSegment; если ничего нет — null (для журнала импорта это допустимо).
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
    }
    return uri.lastPathSegment
}
