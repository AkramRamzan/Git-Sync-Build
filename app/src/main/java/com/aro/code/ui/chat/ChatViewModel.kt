package com.aro.code.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.ai.AntiGravityModel
import com.aro.code.ai.DiffParser
import com.aro.code.ai.DiffPreviewBlock
import com.aro.code.ai.GeminiClient
import com.aro.code.ai.GeminiModel
import com.aro.code.auth.AuthRepository
import com.aro.code.auth.CredentialStore
import com.aro.code.db.ChatHistoryDatabase
import com.aro.code.filesystem.SafProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AttachedFile(
    val name: String,
    val content: String,
    val uriString: String? = null
)

data class ChatMessage(
    val role: String,
    val text: String,
    val attachments: List<AttachedFile> = emptyList(),
    val diffBlocks: List<DiffPreviewBlock> = emptyList(),
    val isStreaming: Boolean = false
)

enum class ChatEngine {
    ANTIGRAVITY,
    GEMINI
}

data class ChatUiState(
    val antiGravityMessages: List<ChatMessage> = emptyList(),
    val geminiMessages: List<ChatMessage> = emptyList(),
    val selectedModel: GeminiModel = GeminiModel.FLASH_1_5,
    val selectedAntiGravityModel: AntiGravityModel = AntiGravityModel.THINKING_2_5,
    val attachedFiles: List<AttachedFile> = emptyList(),
    val isLoading: Boolean = false,
    val activeStep: String? = null,
    val activeDiffForPreview: DiffPreviewBlock? = null,
    val error: String? = null,
    val statusMessage: String? = null,
    val antiGravitySessionId: String = "antigravity_default",
    val geminiSessionId: String = "gemini_default",
    val engine: ChatEngine = ChatEngine.ANTIGRAVITY,
    val isAntiGravitySignedIn: Boolean = false,
    val antiGravityTokenSnippet: String? = null,
    val showAuthDialog: Boolean = false
) {
    val currentMessages: List<ChatMessage>
        get() = if (engine == ChatEngine.ANTIGRAVITY) antiGravityMessages else geminiMessages
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val geminiClient: GeminiClient,
    private val credentialStore: CredentialStore,
    private val projectManager: SafProjectManager,
    private val chatDb: ChatHistoryDatabase,
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val antiGravitySystemPrompt = """
        You are Google Anti-Gravity Agent (Termux CLI agy v2.0), an autonomous software engineering agent.
        You have direct access to inspect workspace files and generate production code modifications.
        
        When proposing code modifications, you MUST output clean, unambiguous diff blocks in the format:
        FILE: <path/to/File.kt>
        <<<<<<< SEARCH
        <exact original lines>
        =======
        <exact replacement lines>
        >>>>>>> REPLACE

        Always format your responses with concise engineering precision.
    """.trimIndent()

    private val geminiSystemPrompt = """
        You are Gemini Developer Assistant inside Aro Code IDE.
        You help developers with coding questions, algorithms, syntax, and general guidance.
    """.trimIndent()

    init {
        // Monitor Anti-Gravity OAuth sign-in state
        viewModelScope.launch {
            credentialStore.googleAccessToken.collect { token ->
                _uiState.update {
                    it.copy(
                        isAntiGravitySignedIn = !token.isNullOrBlank(),
                        antiGravityTokenSnippet = token
                    )
                }
            }
        }

        viewModelScope.launch {
            val savedModelId = credentialStore.geminiModel.firstOrNull()
            if (savedModelId != null) {
                _uiState.update { it.copy(selectedModel = GeminiModel.fromId(savedModelId)) }
            }

            // Restore conversation history from chat_history.db for BOTH engines independently
            runCatching {
                val agMessages = chatDb.getMessagesForSession("antigravity_default")
                val gemMessages = chatDb.getMessagesForSession("gemini_default")

                val mappedAg = agMessages.map { entity ->
                    ChatMessage(
                        role = if (entity.sender == "USER") "user" else "model",
                        text = entity.messageText,
                        attachments = entity.attachedFiles.map { AttachedFile(name = it, content = "", uriString = null) },
                        diffBlocks = DiffParser.parseAllDiffs(entity.messageText),
                        isStreaming = false
                    )
                }
                val mappedGem = gemMessages.map { entity ->
                    ChatMessage(
                        role = if (entity.sender == "USER") "user" else "model",
                        text = entity.messageText,
                        attachments = entity.attachedFiles.map { AttachedFile(name = it, content = "", uriString = null) },
                        diffBlocks = DiffParser.parseAllDiffs(entity.messageText),
                        isStreaming = false
                    )
                }
                _uiState.update {
                    it.copy(
                        antiGravityMessages = mappedAg,
                        geminiMessages = mappedGem
                    )
                }
            }
        }
    }

    fun startNewSession() {
        viewModelScope.launch {
            val currentEngine = _uiState.value.engine
            val newSid = "${if (currentEngine == ChatEngine.ANTIGRAVITY) "antigravity" else "gemini"}_${UUID.randomUUID()}"
            if (currentEngine == ChatEngine.ANTIGRAVITY) {
                _uiState.update {
                    it.copy(
                        antiGravitySessionId = newSid,
                        antiGravityMessages = emptyList(),
                        error = null,
                        statusMessage = "Started fresh Anti-Gravity agent session"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        geminiSessionId = newSid,
                        geminiMessages = emptyList(),
                        error = null,
                        statusMessage = "Started fresh Gemini conversation"
                    )
                }
            }
        }
    }

    fun clearSessionHistory() {
        viewModelScope.launch {
            val currentEngine = _uiState.value.engine
            if (currentEngine == ChatEngine.ANTIGRAVITY) {
                chatDb.clearSession(_uiState.value.antiGravitySessionId)
                _uiState.update {
                    it.copy(
                        antiGravityMessages = emptyList(),
                        error = null,
                        statusMessage = "Cleared Anti-Gravity history"
                    )
                }
            } else {
                chatDb.clearSession(_uiState.value.geminiSessionId)
                _uiState.update {
                    it.copy(
                        geminiMessages = emptyList(),
                        error = null,
                        statusMessage = "Cleared Gemini history"
                    )
                }
            }
        }
    }

    fun switchModel(model: GeminiModel) {
        _uiState.update { it.copy(selectedModel = model) }
        viewModelScope.launch {
            credentialStore.saveGeminiModel(model.id)
        }
    }

    fun switchAntiGravityModel(model: AntiGravityModel) {
        _uiState.update { it.copy(selectedAntiGravityModel = model) }
    }

    fun selectEngine(engine: ChatEngine) {
        _uiState.update { it.copy(engine = engine, error = null, activeStep = null) }
    }

    fun openAuthDialog() = _uiState.update { it.copy(showAuthDialog = true) }
    fun dismissAuthDialog() = _uiState.update { it.copy(showAuthDialog = false) }

    fun submitGoogleAuthCode(
        code: String,
        verifier: String,
        customClientId: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = authRepo.exchangeGoogleAuthCode(code, verifier, customClientId)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        showAuthDialog = false,
                        isLoading = false,
                        statusMessage = "Anti-Gravity Agent connected ✓"
                    )
                }
                onResult(Result.success(Unit))
            } else {
                val err = result.exceptionOrNull() ?: Exception("Invalid code")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Anti-Gravity login failed: ${err.message}"
                    )
                }
                onResult(Result.failure(err))
            }
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            credentialStore.clearGoogleTokens()
            _uiState.update {
                it.copy(
                    showAuthDialog = false,
                    statusMessage = "Signed out of Anti-Gravity"
                )
            }
        }
    }

    fun attachFile(name: String, content: String, uriString: String? = null) {
        val attached = AttachedFile(name = name, content = content, uriString = uriString)
        _uiState.update { it.copy(attachedFiles = it.attachedFiles + attached) }
    }

    fun removeAttachment(index: Int) {
        _uiState.update {
            val list = it.attachedFiles.toMutableList()
            if (index in list.indices) list.removeAt(index)
            it.copy(attachedFiles = list)
        }
    }

    fun showDiffPreview(diff: DiffPreviewBlock) {
        _uiState.update { it.copy(activeDiffForPreview = diff) }
    }

    fun dismissDiffPreview() {
        _uiState.update { it.copy(activeDiffForPreview = null) }
    }

    fun applyDiff(diff: DiffPreviewBlock) {
        viewModelScope.launch {
            val uriStr = diff.targetUriString ?: _uiState.value.attachedFiles
                .firstOrNull { it.name == diff.targetFileName }?.uriString

            if (uriStr != null) {
                val uri = android.net.Uri.parse(uriStr)
                projectManager.atomicWriteText(uri, diff.replacementContent).onSuccess {
                    _uiState.update {
                        it.copy(
                            activeDiffForPreview = null,
                            statusMessage = "Diff applied successfully to ${diff.targetFileName} ✓"
                        )
                    }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(error = "Failed to apply diff: ${e.message}")
                    }
                }
            } else {
                _uiState.update {
                    it.copy(error = "Cannot apply diff: File URI not found for ${diff.targetFileName}")
                }
            }
        }
    }

    private fun appendMessage(msg: ChatMessage, isAntiGravity: Boolean) {
        _uiState.update { state ->
            if (isAntiGravity) {
                state.copy(antiGravityMessages = state.antiGravityMessages + msg)
            } else {
                state.copy(geminiMessages = state.geminiMessages + msg)
            }
        }
    }

    private fun updateLastMessage(isAntiGravity: Boolean, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            if (isAntiGravity) {
                val list = state.antiGravityMessages.toMutableList()
                if (list.isNotEmpty()) {
                    list[list.lastIndex] = transform(list[list.lastIndex])
                }
                state.copy(antiGravityMessages = list)
            } else {
                val list = state.geminiMessages.toMutableList()
                if (list.isNotEmpty()) {
                    list[list.lastIndex] = transform(list[list.lastIndex])
                }
                state.copy(geminiMessages = list)
            }
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() && _uiState.value.attachedFiles.isEmpty()) return

        val currentAttachments = _uiState.value.attachedFiles
        val isAntiGravity = _uiState.value.engine == ChatEngine.ANTIGRAVITY
        val currentSessionId = if (isAntiGravity) _uiState.value.antiGravitySessionId else _uiState.value.geminiSessionId

        val userMsg = ChatMessage(
            role = "user",
            text = userText,
            attachments = currentAttachments
        )

        appendMessage(userMsg, isAntiGravity)
        _uiState.update {
            it.copy(
                attachedFiles = emptyList(),
                isLoading = true,
                activeStep = if (isAntiGravity) "⚡ [1/3] agy: Scanning project files & context..." else null,
                error = null
            )
        }

        viewModelScope.launch {
            // Save user message in local SQLite chat_history.db (Module 2.3)
            runCatching {
                chatDb.insertMessage(currentSessionId, "USER", userText, currentAttachments.map { it.name })
            }

            val authKey: String
            val activeSystemPrompt: String
            val targetModelId: String

            if (isAntiGravity) {
                val googleToken = credentialStore.googleAccessToken.firstOrNull()?.takeIf { it.isNotBlank() }
                if (googleToken == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeStep = null,
                            error = "Anti-Gravity account not connected. Please tap 'Sign In' to login with Google Anti-Gravity."
                        )
                    }
                    return@launch
                }
                authKey = googleToken
                activeSystemPrompt = antiGravitySystemPrompt
                targetModelId = _uiState.value.selectedAntiGravityModel.id
                _uiState.update {
                    it.copy(activeStep = "🧠 [2/3] agy: Reasoning (${_uiState.value.selectedAntiGravityModel.displayName.take(15)})...")
                }
            } else {
                val apiKey = credentialStore.geminiApiKey.firstOrNull()?.takeIf { it.isNotBlank() }
                if (apiKey == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No Gemini API key set. Please configure your Gemini API Key in Settings (⚙)."
                        )
                    }
                    return@launch
                }
                authKey = apiKey
                activeSystemPrompt = geminiSystemPrompt
                targetModelId = _uiState.value.selectedModel.id
            }

            // Construct prompt with attached file contexts
            val promptBuilder = StringBuilder()
            if (currentAttachments.isNotEmpty()) {
                promptBuilder.appendLine("=== ATTACHED PROJECT FILES ===")
                currentAttachments.forEach { file ->
                    promptBuilder.appendLine("FILE: ${file.name}")
                    promptBuilder.appendLine("```")
                    promptBuilder.appendLine(file.content)
                    promptBuilder.appendLine("```\n")
                }
            }
            promptBuilder.appendLine("USER INSTRUCTION:")
            promptBuilder.appendLine(userText)

            val fullUserPrompt = promptBuilder.toString()

            // Prepare placeholder assistant message for streaming
            val assistantMsg = ChatMessage(role = "model", text = "", isStreaming = true)
            appendMessage(assistantMsg, isAntiGravity)

            val responseAccumulator = StringBuilder()

            geminiClient.streamGenerate(
                apiKey = authKey,
                modelId = targetModelId,
                systemPrompt = activeSystemPrompt,
                userPrompt = fullUserPrompt
            ).catch { e ->
                val errorPrefix = if (isAntiGravity) "⚡ Anti-Gravity Agent Error" else "⚠️ Gemini Error"
                val errorMsg = "$errorPrefix: ${e.message ?: "Failed to generate response"}"
                updateLastMessage(isAntiGravity) { it.copy(text = errorMsg, isStreaming = false) }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        activeStep = null,
                        error = e.message
                    )
                }
            }.collect { token ->
                responseAccumulator.append(token)
                val currentText = responseAccumulator.toString()
                if (isAntiGravity && _uiState.value.activeStep != "✏️ [3/3] agy: Generating unified diff & code changes...") {
                    _uiState.update { it.copy(activeStep = "✏️ [3/3] agy: Generating unified diff & code changes...") }
                }
                updateLastMessage(isAntiGravity) { it.copy(text = currentText) }
            }

            // Stream completed: finalize message and parse any diff blocks
            val finalText = responseAccumulator.toString().ifBlank {
                if (_uiState.value.error != null) "" else "⚠️ No response received."
            }
            val parsedDiffs = if (finalText.isNotBlank()) DiffParser.parseAllDiffs(finalText) else emptyList()

            // Persist agent response in local SQLite chat_history.db (Module 2.3)
            if (finalText.isNotBlank()) {
                runCatching {
                    chatDb.insertMessage(currentSessionId, "AGENT", finalText, emptyList())
                }
            }

            updateLastMessage(isAntiGravity) { current ->
                val textToDisplay = if (current.text.startsWith("⚡ Anti-Gravity Agent Error") || current.text.startsWith("⚠️ Gemini Error")) current.text else finalText
                current.copy(
                    text = textToDisplay,
                    diffBlocks = parsedDiffs,
                    isStreaming = false
                )
            }
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    activeStep = null
                )
            }
        }
    }
}
