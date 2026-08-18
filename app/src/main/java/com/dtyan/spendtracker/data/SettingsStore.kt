package com.dtyan.spendtracker.data

import android.content.Context
import android.content.SharedPreferences
import com.dtyan.spendtracker.notifications.BankCatalog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** Пользовательские настройки автоучёта из уведомлений. */
data class AutoCaptureSettings(
    /** Главный переключатель: читать ли уведомления вообще. */
    val enabled: Boolean = false,
    /** Коды банков, чьи уведомления читаем. */
    val enabledBanks: Set<String> = BankCatalog.defaultEnabledCodes,
    /** Показывать ли своё уведомление о новой распознанной операции. */
    val notifyOnCapture: Boolean = true,
    /** Онбординг про доступ к уведомлениям уже показывали. */
    val onboardingShown: Boolean = false,
) {
    fun isBankEnabled(code: String): Boolean = enabled && code in enabledBanks
}

/**
 * Хранилище настроек на SharedPreferences.
 *
 * Отдельный слой нужен, потому что настройки читает и UI, и сервис уведомлений
 * (у которого нет ViewModel). Изменения отдаются потоком — экран настроек и сервис
 * видят одно и то же состояние без ручной синхронизации.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Текущее состояние — синхронно, для сервиса уведомлений. */
    fun current(): AutoCaptureSettings = AutoCaptureSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        enabledBanks = prefs.getStringSet(KEY_BANKS, null) ?: BankCatalog.defaultEnabledCodes,
        notifyOnCapture = prefs.getBoolean(KEY_NOTIFY, true),
        onboardingShown = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    /** Поток настроек: эмитит текущее значение и все последующие изменения. */
    fun observe(): Flow<AutoCaptureSettings> = callbackFlow {
        trySend(current())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeEnabled(): Flow<Boolean> = observe().map { it.enabled }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setBankEnabled(code: String, enabled: Boolean) {
        val banks = current().enabledBanks.toMutableSet()
        if (enabled) banks.add(code) else banks.remove(code)
        prefs.edit().putStringSet(KEY_BANKS, banks).apply()
    }

    fun setNotifyOnCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY, enabled).apply()
    }

    fun setOnboardingShown() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).apply()
    }

    private companion object {
        const val NAME = "spendtracker_settings"
        const val KEY_ENABLED = "auto_capture_enabled"
        const val KEY_BANKS = "auto_capture_banks"
        const val KEY_NOTIFY = "auto_capture_notify"
        const val KEY_ONBOARDING = "auto_capture_onboarding_shown"
    }
}
