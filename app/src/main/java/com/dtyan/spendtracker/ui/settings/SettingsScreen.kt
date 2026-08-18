package com.dtyan.spendtracker.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dtyan.spendtracker.BuildConfig
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.notifications.BankCatalog
import com.dtyan.spendtracker.notifications.NotificationAccess

/**
 * «Ещё» — настройки автоучёта из уведомлений и переходы в остальные разделы.
 *
 * Здесь же живёт всё, что нужно, чтобы включить автоучёт после того, как онбординг
 * при первом запуске был пропущен: и системный доступ, и список банков.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val context = LocalContext.current
    val state by settings.observe().collectAsState(initial = settings.current())

    // Доступ к уведомлениям выдаётся в системных настройках, поэтому перечитываем его
    // каждый раз, когда экран снова становится активным.
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = NotificationAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> settings.setNotifyOnCapture(granted) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ещё") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "auto-capture") {
                AutoCaptureCard(
                    enabled = state.enabled,
                    accessGranted = accessGranted,
                    notifyOnCapture = state.notifyOnCapture,
                    onToggle = { enabled ->
                        settings.setEnabled(enabled)
                        // Включили автоучёт, а системного доступа нет — сразу ведём выдавать.
                        if (enabled && !NotificationAccess.isGranted(context)) {
                            openNotificationAccess(context)
                        }
                    },
                    onOpenAccess = { openNotificationAccess(context) },
                    onToggleNotify = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            settings.setNotifyOnCapture(enabled)
                        }
                    },
                )
            }

            item(key = "banks") {
                AnimatedVisibility(visible = state.enabled) {
                    BanksCard(
                        enabledBanks = state.enabledBanks,
                        isInstalled = { source -> NotificationAccess.isInstalled(context, source) },
                        onToggle = settings::setBankEnabled,
                    )
                }
            }

            item(key = "sections") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionRow(
                            title = "Импорт из банка",
                            subtitle = "Загрузить выписку файлом",
                            icon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            onClick = onOpenImport,
                        )
                        HorizontalDivider()
                        SectionRow(
                            title = "Экспорт",
                            subtitle = "CSV, JSON и отчёт для ИИ-анализа",
                            icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = onOpenExport,
                        )
                        HorizontalDivider()
                        SectionRow(
                            title = "Категории",
                            subtitle = "Свои категории и подкатегории",
                            icon = { Icon(Icons.Filled.Category, contentDescription = null) },
                            onClick = onOpenCategories,
                        )
                    }
                }
            }

            item(key = "about") { AboutCard() }
        }
    }
}

@Composable
private fun AutoCaptureCard(
    enabled: Boolean,
    accessGranted: Boolean,
    notifyOnCapture: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenAccess: () -> Unit,
    onToggleNotify: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Траты из уведомлений",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Приложение разбирает уведомления банков и складывает операции " +
                            "в «Черновики». В траты и статистику они попадают только после " +
                            "вашего подтверждения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    AccessStatus(accessGranted = accessGranted, onOpenAccess = onOpenAccess)

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Уведомлять о новых операциях", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Пуш с кнопками «Подтвердить» и «Отклонить» — разобрать трату " +
                                    "можно, не открывая приложение",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = notifyOnCapture, onCheckedChange = onToggleNotify)
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Тексты уведомлений остаются на устройстве: у приложения нет " +
                            "разрешения на интернет. Читаются только приложения банков из списка ниже.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessStatus(accessGranted: Boolean, onOpenAccess: () -> Unit) {
    val container = if (accessGranted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (accessGranted) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (accessGranted) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (accessGranted) "Доступ к уведомлениям выдан" else "Нужен доступ к уведомлениям",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!accessGranted) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Это системное разрешение — его нельзя выдать из приложения. " +
                        "Откройте настройки и включите «Траты» в списке.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenAccess) { Text("Открыть настройки Android") }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Отозвать доступ можно там же — в системных настройках.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenAccess) { Text("Открыть настройки Android") }
            }
        }
    }
}

@Composable
private fun BanksCard(
    enabledBanks: Set<String>,
    isInstalled: (com.dtyan.spendtracker.notifications.BankSource) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "Какие приложения читать",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            BankCatalog.sources.forEach { source ->
                val installed = remember(source.code) { isInstalled(source) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = when {
                                source.isSms -> "Только сообщения от банковских отправителей"
                                installed -> "Приложение установлено"
                                else -> "Приложение не найдено на устройстве"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = source.code in enabledBanks,
                        onCheckedChange = { onToggle(source.code, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Траты, версия ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Все данные хранятся только на устройстве. Ни аккаунтов, ни сети, ни аналитики.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.height(4.dp))
        }
    }
}

/** Открывает системный экран «Доступ к уведомлениям». */
private fun openNotificationAccess(context: Context) {
    runCatching { context.startActivity(NotificationAccess.settingsIntent()) }
}
