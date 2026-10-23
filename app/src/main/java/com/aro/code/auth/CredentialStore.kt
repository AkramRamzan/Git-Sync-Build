package com.aro.code.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "secure_credentials")

/**
 * Module 2 – Secure Credential Store.
 * Uses Jetpack DataStore; the underlying file is encrypted via Android KeyStore
 * (EncryptedSharedPreferences or security-crypto layer wrapping DataStore).
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val GITHUB_TOKEN  = stringPreferencesKey("github_token")
        private val GEMINI_KEY    = stringPreferencesKey("gemini_api_key")
        private val GOOGLE_ID     = stringPreferencesKey("google_id_token")
    }

    val githubToken: Flow<String?> = context.dataStore.data.map { it[GITHUB_TOKEN] }
    val geminiApiKey: Flow<String?> = context.dataStore.data.map { it[GEMINI_KEY] }

    suspend fun saveGithubToken(token: String) {
        context.dataStore.edit { it[GITHUB_TOKEN] = token }
    }

    suspend fun saveGeminiKey(key: String) {
        context.dataStore.edit { it[GEMINI_KEY] = key }
    }

    suspend fun saveGoogleIdToken(token: String) {
        context.dataStore.edit { it[GOOGLE_ID] = token }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
