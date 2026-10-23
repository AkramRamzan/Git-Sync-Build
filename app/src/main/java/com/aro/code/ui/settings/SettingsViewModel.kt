package com.aro.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.auth.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val geminiKeyMasked:  String?  = null,   // e.g. "AIza••••••••••••xxYZ"
    val githubTokenMasked: String? = null,   // e.g. "ghp_••••••••••••abcd"
    val isLoading: Boolean         = false,
    val successMessage: String?    = null,
    val errorMessage: String?      = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init { loadMasked() }

    // ── Load masked previews ────────────────────────────────────────────────
    private fun loadMasked() {
        viewModelScope.launch {
            combine(
                credentialStore.geminiApiKey,
                credentialStore.githubToken
            ) { gemini, github ->
                SettingsUiState(
                    geminiKeyMasked   = gemini?.mask(),
                    githubTokenMasked = github?.mask()
                )
            }.collect { _uiState.value = it }
        }
    }

    // ── Gemini API Key ──────────────────────────────────────────────────────
    fun saveGeminiKey(key: String) {
        viewModelScope.launch {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "API key cannot be empty") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            runCatching { credentialStore.saveGeminiKey(trimmed) }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false,
                            geminiKeyMasked = trimmed.mask(),
                            successMessage  = "Gemini API key saved ✓")
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false,
                            errorMessage = "Save failed: ${e.message}")
                    }
                }
        }
    }

    fun deleteGeminiKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching { credentialStore.saveGeminiKey("") }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false,
                            geminiKeyMasked = null,
                            successMessage  = "Gemini API key removed")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                }
        }
    }

    // ── GitHub Token ────────────────────────────────────────────────────────
    fun saveGithubToken(token: String) {
        viewModelScope.launch {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "GitHub token cannot be empty") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            runCatching { credentialStore.saveGithubToken(trimmed) }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false,
                            githubTokenMasked = trimmed.mask(),
                            successMessage    = "GitHub token saved ✓")
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Save failed: ${e.message}")
                    }
                }
        }
    }

    fun deleteGithubToken() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching { credentialStore.saveGithubToken("") }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false,
                            githubTokenMasked = null,
                            successMessage    = "GitHub token removed")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                }
        }
    }

    // ── Delete ALL credentials ───────────────────────────────────────────────
    fun deleteAllCredentials() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching { credentialStore.clearAll() }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false,
                            geminiKeyMasked   = null,
                            githubTokenMasked = null,
                            successMessage    = "All credentials cleared")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessage = null, errorMessage = null) }
    }

    // ── Helper ───────────────────────────────────────────────────────────────
    private fun String.mask(): String {
        if (length <= 8) return "••••••••"
        val prefix = take(4)
        val suffix = takeLast(4)
        val middle = "•".repeat(minOf(length - 8, 12))
        return "$prefix$middle$suffix"
    }
}
