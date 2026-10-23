package com.aro.code.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.ai.GeminiClient
import com.aro.code.auth.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(val role: String, val text: String)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean          = false,
    val error: String?              = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val geminiClient: GeminiClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val systemPrompt = """
        You are an expert Android developer assistant embedded in the Aro Code IDE.
        When asked to modify code, always respond with SEARCH/REPLACE blocks in this format:
        <<<<<<< SEARCH
        <original lines>
        =======
        <replacement lines>
        >>>>>>> REPLACE
        Keep your explanation brief and always produce valid Kotlin/Java/XML.
    """.trimIndent()

    fun send(userText: String) {
        val userMsg = ChatMessage("user", userText)
        _uiState.update { it.copy(messages = it.messages + userMsg, isLoading = true) }
        viewModelScope.launch {
            val key = credentialStore.geminiApiKey.firstOrNull() ?: run {
                _uiState.update { it.copy(isLoading = false,
                    error = "No Gemini API key set. Add it in Settings.") }
                return@launch
            }
            geminiClient.generate(
                apiKey       = key,
                systemPrompt = systemPrompt,
                userPrompt   = userText
            ).onSuccess { reply ->
                _uiState.update {
                    it.copy(
                        messages  = it.messages + ChatMessage("model", reply),
                        isLoading = false
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
