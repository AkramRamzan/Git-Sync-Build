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
 * Module 2 & Anti-Gravity – Secure Credential Store.
 * Uses Jetpack DataStore to persist OAuth tokens, API keys, and project settings.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val GITHUB_TOKEN         = stringPreferencesKey("github_token")
        private val GEMINI_KEY           = stringPreferencesKey("gemini_api_key")
        private val GEMINI_MODEL         = stringPreferencesKey("gemini_model")
        private val GOOGLE_ID            = stringPreferencesKey("google_id_token")
        private val GOOGLE_ACCESS_TOKEN  = stringPreferencesKey("google_access_token")
        private val GOOGLE_REFRESH_TOKEN = stringPreferencesKey("google_refresh_token")
        private val PROJECT_DIR_URI      = stringPreferencesKey("project_dir_uri")
        private val ANTIGRAVITY_BRIDGE_URL = stringPreferencesKey("antigravity_bridge_url")
        private val ANTIGRAVITY_BRIDGE_TOKEN = stringPreferencesKey("antigravity_bridge_token")
    }

    val githubToken: Flow<String?> = context.dataStore.data.map { it[GITHUB_TOKEN] }
    val geminiApiKey: Flow<String?> = context.dataStore.data.map { it[GEMINI_KEY] }
    val geminiModel: Flow<String?> = context.dataStore.data.map { it[GEMINI_MODEL] }
    val googleIdToken: Flow<String?> = context.dataStore.data.map { it[GOOGLE_ID] }
    val googleAccessToken: Flow<String?> = context.dataStore.data.map { it[GOOGLE_ACCESS_TOKEN] }
    val googleRefreshToken: Flow<String?> = context.dataStore.data.map { it[GOOGLE_REFRESH_TOKEN] }
    val projectDirUri: Flow<String?> = context.dataStore.data.map { it[PROJECT_DIR_URI] }
    val antigravityBridgeUrl: Flow<String?> = context.dataStore.data.map { it[ANTIGRAVITY_BRIDGE_URL] }
    val antigravityBridgeToken: Flow<String?> = context.dataStore.data.map { it[ANTIGRAVITY_BRIDGE_TOKEN] }

    suspend fun saveGithubToken(token: String) {
        context.dataStore.edit { it[GITHUB_TOKEN] = token }
    }

    suspend fun saveGeminiKey(key: String) {
        context.dataStore.edit { it[GEMINI_KEY] = key }
    }

    suspend fun saveGeminiModel(modelId: String) {
        context.dataStore.edit { it[GEMINI_MODEL] = modelId }
    }

    suspend fun saveGoogleTokens(accessToken: String, refreshToken: String?, idToken: String?) {
        context.dataStore.edit {
            it[GOOGLE_ACCESS_TOKEN] = accessToken
            if (!refreshToken.isNullOrBlank()) {
                it[GOOGLE_REFRESH_TOKEN] = refreshToken
            }
            if (!idToken.isNullOrBlank()) {
                it[GOOGLE_ID] = idToken
            }
        }
    }

    suspend fun saveGoogleIdToken(token: String) {
        context.dataStore.edit { it[GOOGLE_ID] = token }
    }

    suspend fun clearGoogleTokens() {
        context.dataStore.edit {
            it.remove(GOOGLE_ACCESS_TOKEN)
            it.remove(GOOGLE_REFRESH_TOKEN)
            it.remove(GOOGLE_ID)
        }
    }

    suspend fun saveProjectDirUri(uri: String?) {
        context.dataStore.edit {
            if (uri != null) {
                it[PROJECT_DIR_URI] = uri
            } else {
                it.remove(PROJECT_DIR_URI)
            }
        }
    }

    suspend fun saveAntiGravityBridge(url: String, token: String? = null) {
        context.dataStore.edit {
            it[ANTIGRAVITY_BRIDGE_URL] = url
            if (!token.isNullOrBlank()) {
                it[ANTIGRAVITY_BRIDGE_TOKEN] = token
            }
        }
    }

    suspend fun clearAntiGravityBridge() {
        context.dataStore.edit {
            it.remove(ANTIGRAVITY_BRIDGE_URL)
            it.remove(ANTIGRAVITY_BRIDGE_TOKEN)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
