package com.dtyan.spendtracker.ui.exportui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.Period
import com.dtyan.spendtracker.export.AnalysisBundle
import com.dtyan.spendtracker.export.CsvExporter
import com.dtyan.spendtracker.export.ExportFormat
import com.dtyan.spendtracker.export.ExportManager
import com.dtyan.spendtracker.export.JsonExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val APP_VERSION = "1.0"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(repository: ExpenseRepository) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val expensesFlow = remember(repository) { repository.observeExpenses() }
    val records by expensesFlow.collectAsState(initial = emptyList())

    // Какой формат сейчас готовится (для индикатора на кнопке).
    var busyFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var copying by remember { mutableStateOf(false) }
    val busy = busyFormat != null || copying
    val hasData = records.isNotEmpty()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Экспорт") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            IntroCard()

            DataSummaryCard(records)

            ExportFormat.entries.forEach { format ->
                ExportCard(
                    format = format,
                    enabled = hasData && !busy,
                    loading = busyFormat == format,
                    onShare = {
                        busyFormat = format
                        scope.launch {
                            val result = runCatching {
                                val content = withContext(Dispatchers.IO) {
                                    buildContent(format, records)
                                }
                                val manager = ExportManager(context)
                                val uri = withContext(Dispatchers.IO) {
                                    manager.write(format, content, LocalDate.now())
                                }
                                context.startActivity(manager.shareIntent(uri, format))
                            }
                            busyFormat = null
                            if (result.isFailure) {
                                snackbarHostState.showSnackbar("Не удалось поделиться файлом")
                            }
                        }
                    },
                )
            }

            // Отчёт для ИИ часто проще вставить в чат текстом, чем прикладывать файлом.
            OutlinedButton(
                onClick = {
                    copying = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                AnalysisBundle.build(records, LocalDate.now())
                            }
                        }
                        copying = false
                        result.onSuccess {
                            clipboard.setText(AnnotatedString(it))
                            snackbarHostState.showSnackbar("Скопировано")
                        }.onFailure {
                            snackbarHostState.showSnackbar("Не удалось собрать отчёт")
                        }
                    }
                },
                enabled = hasData && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (copying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Скопировать отчёт для ИИ в буфер")
            }

            if (!hasData) {
                Text(
                    text = "Нечего экспортировать — сначала добавьте траты.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Зачем это нужно",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Выгрузите свои траты одним файлом и отдайте его языковой модели " +
                    "(ChatGPT, Claude, Алиса) с просьбой разобрать расходы и подсказать, " +
                    "где можно сэкономить. Данные никуда не отправляются сами — " +
                    "файл создаётся на телефоне, а делитесь им вы.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DataSummaryCard(records: List<ExpenseRecord>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Что будет в выгрузке",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (records.isEmpty()) {
                Text(
                    text = "Пока нет ни одной траты.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val from = records.minOf { it.date }
                val to = records.maxOf { it.date }
                val total = records.sumOf { it.amountMinor }
                Text("Всего трат: ${records.size}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Период: с ${from.format(Period.DAY_FORMAT)} по ${to.format(Period.DAY_FORMAT)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Общая сумма: ${MoneyFormat.format(total)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ExportCard(
    format: ExportFormat,
    enabled: Boolean,
    loading: Boolean,
    onShare: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = format.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = format.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = format.description(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onShare,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Поделиться")
            }
        }
    }
}

// --- утилиты ---

private fun buildContent(format: ExportFormat, records: List<ExpenseRecord>): String =
    when (format) {
        ExportFormat.CSV -> CsvExporter.export(records)
        ExportFormat.JSON -> JsonExporter.export(records, System.currentTimeMillis(), APP_VERSION)
        ExportFormat.ANALYSIS -> AnalysisBundle.build(records, LocalDate.now())
    }

private fun ExportFormat.description(): String = when (this) {
    ExportFormat.CSV -> "Таблица для Excel/Google Sheets"
    ExportFormat.JSON -> "Полные данные, удобно для программной обработки"
    ExportFormat.ANALYSIS ->
        "Готовый markdown-отчёт со сводкой и таблицами — просто приложите его в чат"
}

private fun ExportFormat.icon(): ImageVector = when (this) {
    ExportFormat.CSV -> Icons.Filled.TableChart
    ExportFormat.JSON -> Icons.Filled.Description
    ExportFormat.ANALYSIS -> Icons.Filled.AutoAwesome
}
