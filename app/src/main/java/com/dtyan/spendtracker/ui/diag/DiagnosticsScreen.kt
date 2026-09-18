package com.dtyan.spendtracker.ui.diag

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtyan.spendtracker.BuildConfig
import com.dtyan.spendtracker.data.DiagnosticsLog
import com.dtyan.spendtracker.data.LogLevel
import com.dtyan.spendtracker.data.LogRecord
import com.dtyan.spendtracker.data.LogStage
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.export.DiagnosticsExporter
import com.dtyan.spendtracker.export.ExportFormat
import com.dtyan.spendtracker.export.ExportManager
import com.dtyan.spendtracker.notifications.NotificationAccess
import com.dtyan.spendtracker.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

/**
 * «Журнал автоучёта» — что приложение увидело и что с этим сделало.
 *
 * Экран нужен для разбора инцидентов: видно каждое уведомление с исходным текстом,
 * решение парсера с причиной, дедупликацию, подбор категории и выбор пользователя.
 * Журнал выгружается одним файлом — его можно приложить к описанию проблемы.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    log: DiagnosticsLog,
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val records by log.observeRecent().collectAsState(initial = emptyList())
    val total by log.observeCount().collectAsState(initial = 0)
    val appSettings by settings.observe().collectAsState(initial = settings.current())

    var stageFilter by remember { mutableStateOf<LogStage?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    val visible = records.filter { stageFilter == null || it.stage == stageFilter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал автоучёта") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить журнал")
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Вести журнал",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Записей: $total. Хранятся последние 3000, " +
                                        "только на устройстве.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = appSettings.diagnosticsEnabled,
                                onCheckedChange = settings::setDiagnosticsEnabled,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "В журнал попадают тексты банковских уведомлений — " +
                                "выгружайте файл только тому, кому доверяете.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    shareLog(context, log, settings)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Выгрузить журнал файлом")
                        }
                    }
                }
            }

            item(key = "filters") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = stageFilter == null,
                        onClick = { stageFilter = null },
                        label = { Text("Все") },
                    )
                    LogStage.entries.forEach { stage ->
                        FilterChip(
                            selected = stageFilter == stage,
                            onClick = { stageFilter = if (stageFilter == stage) null else stage },
                            label = { Text(stage.title) },
                        )
                    }
                }
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = if (appSettings.diagnosticsEnabled) {
                                "Пока пусто. События появятся, когда придёт уведомление от банка " +
                                    "или вы разберёте операцию в «Черновиках»."
                            } else {
                                "Журнал выключен — новые события не записываются."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(visible, key = { it.id }) { record ->
                    LogRow(
                        record = record,
                        expanded = expanded == record.id,
                        onToggle = { expanded = if (expanded == record.id) null else record.id },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "Очистить журнал?",
            text = "Все записи будут удалены. На траты и черновики это не влияет.",
            confirmText = "Очистить",
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                scope.launch { log.clear() }
            },
        )
    }
}

@Composable
private fun LogRow(record: LogRecord, expanded: Boolean, onToggle: () -> Unit) {
    val levelColor = when (record.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.at.format(TIME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.stage.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(text = record.message, style = MaterialTheme.typography.bodyMedium)
            if (record.details != null) {
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = record.details,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "нажмите, чтобы раскрыть подробности",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Собирает файл журнала и отдаёт его в системное меню «Поделиться». */
private suspend fun shareLog(context: Context, log: DiagnosticsLog, settings: SettingsStore) {
    runCatching {
        val content = withContext(Dispatchers.IO) {
            DiagnosticsExporter.build(
                records = log.getAll(),
                settings = settings.current(),
                environment = listOf(
                    "версия приложения" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "устройство" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "доступ к уведомлениям" to if (NotificationAccess.isGranted(context)) "выдан" else "не выдан",
                ),
            )
        }
        val manager = ExportManager(context)
        val uri = withContext(Dispatchers.IO) {
            manager.write(ExportFormat.LOG, content, LocalDate.now())
        }
        context.startActivity(manager.shareIntent(uri, ExportFormat.LOG))
    }
}
