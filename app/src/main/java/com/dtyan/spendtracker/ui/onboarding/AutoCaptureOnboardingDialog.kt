package com.dtyan.spendtracker.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Онбординг автоучёта при первом запуске.
 *
 * Показывается один раз и честно объясняет, что именно приложение будет читать и зачем.
 * «Не сейчас» — полноценный сценарий: включить автоучёт можно позже во вкладке «Ещё».
 */
@Composable
fun AutoCaptureOnboardingDialog(
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        icon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
        title = { Text("Считывать траты из уведомлений банков?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Приложение может разбирать уведомления банковских приложений и складывать " +
                        "покупки в раздел «Черновики». Вечером вы просматриваете их и подтверждаете " +
                        "нужные — вводить траты руками почти не придётся.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Bullet("Ничего не добавляется в траты и статистику само — только после подтверждения.")
                Bullet("Читаются только приложения банков из списка, остальные уведомления не разбираются.")
                Bullet("Данные остаются на телефоне: у приложения нет доступа в интернет.")
                Text(
                    text = "Доступ к уведомлениям выдаётся в системных настройках Android — " +
                        "мы просто откроем нужный экран. Передумать можно в любой момент во вкладке «Ещё».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("Включить") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Не сейчас") } },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
