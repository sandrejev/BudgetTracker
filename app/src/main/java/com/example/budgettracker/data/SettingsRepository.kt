package com.example.budgettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.budgettracker.receipt.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val MONTHLY_LIMIT_KEY = doublePreferencesKey("monthly_limit")
        private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val LLM_API_URL = stringPreferencesKey("llm_api_url")
        private const val DEFAULT_LIMIT = 600.0
    }

    val monthlyLimitFlow: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[MONTHLY_LIMIT_KEY] ?: DEFAULT_LIMIT
    }

    suspend fun setMonthlyLimit(limit: Double) {
        context.dataStore.edit { it[MONTHLY_LIMIT_KEY] = limit }
    }

    val llmApiKeyFlow: Flow<String> = context.dataStore.data.map { it[LLM_API_KEY] ?: "" }
    // Blank or unset falls back to the default Gemini URL so the settings field is prefilled.
    val llmApiUrlFlow: Flow<String> = context.dataStore.data.map {
        it[LLM_API_URL]?.takeIf { url -> url.isNotBlank() } ?: LlmClient.DEFAULT_GEMINI_URL
    }

    suspend fun setLlmApiKey(key: String) {
        context.dataStore.edit { it[LLM_API_KEY] = key }
    }

    suspend fun setLlmApiUrl(url: String) {
        context.dataStore.edit { it[LLM_API_URL] = url }
    }
}
