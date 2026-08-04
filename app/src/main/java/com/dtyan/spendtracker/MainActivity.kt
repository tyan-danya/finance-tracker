package com.dtyan.spendtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        val repository = (application as SpendApp).container.repository
        setContent {
            SpendTrackerTheme {
                AppNav(repository)
            }
        }
    }
}
