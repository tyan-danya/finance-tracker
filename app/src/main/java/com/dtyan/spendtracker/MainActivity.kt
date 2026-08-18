package com.dtyan.spendtracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import com.dtyan.spendtracker.notifications.PendingNotifier
import com.dtyan.spendtracker.ui.nav.AppNav
import com.dtyan.spendtracker.ui.theme.SpendTrackerTheme

/**
 * Единственная Activity приложения: тема + навигация.
 * Репозиторий берём из контейнера приложения и прокидываем вниз явным параметром —
 * DI-фреймворк здесь избыточен.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SpendApp).container
        // Канал создаём заранее: иначе первое уведомление о распознанной операции не покажется.
        PendingNotifier(applicationContext).ensureChannel()

        setContent {
            // Каждое нажатие на наше уведомление увеличивает счётчик — навигация в «Черновики»
            // срабатывает и при повторном нажатии (activity в singleTask переиспользуется).
            var openPendingTicket by remember { mutableIntStateOf(if (intent.shouldOpenPending()) 1 else 0) }
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { newIntent ->
                    if (newIntent.shouldOpenPending()) openPendingTicket++
                }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            SpendTrackerTheme {
                AppNav(
                    repository = container.repository,
                    settings = container.settings,
                    openPendingTicket = openPendingTicket,
                )
            }
        }
    }

    companion object {
        /** Открыть очередь подтверждения сразу после запуска (нажатие на наше уведомление). */
        const val EXTRA_OPEN_PENDING = "open_pending"
    }
}

private fun Intent?.shouldOpenPending(): Boolean =
    this?.getBooleanExtra(MainActivity.EXTRA_OPEN_PENDING, false) == true
