package com.aro.code.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.ai.DiffParser
import com.aro.code.ai.DiffPreviewBlock
import com.aro.code.ai.GeminiClient
import com.aro.code.ai.GeminiModel
import com.aro.code.auth.CredentialStore
import com.aro.code.filesystem.SafProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val selectedModel: GeminiModel = GeminiModel.FLASH_3_8,
    val attachedFiles: List<AttachedFile> = emptyList(),
    val isLoading: Boolean = false,
    val activeDiffForPreview: DiffPreviewBlock? = null,
    val error: String? = null,
    val statusMessage: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val geminiClient: GeminiClient,
    private val credentialStore: CredentialStore,
    private val projectManager: SafProjectManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val systemPrompt = """
        You are an expert Android developer assistant embedded in the Aro Code IDE.
        You can inspect provided files and suggest edits.
        When modifying code, respond with clear diff blocks in either format:
        
        FILE: <path/to/File.kt>
        <<<<<<< SEARCH
        <exact original lines>
        =======
        <exact replacement lines>
        >>>>>>> REPLACE
        
        OR standard markdown diff:
        ```diff
        --- a/<path/to/File.kt>
        +++ b/<path/to/File.kt>
        @@ -line,len +line,len @@
        - <removed line>
        + <added line>
        ```
        
        Keep explanations concise and focused on high-quality production Kotlin/Java/XML code.
    """.trimIndent()

    init {
        viewModelScope.launch {
            val savedModelId = credentialStore.geminiModel.firstOrNull()
            if (savedModelId != null) {
                _uiState.update { it.copy(selectedModel = GeminiModel.fromId(savedModelId)) }
            }
        }
    }

    fun switchModel(model: GeminiModel) {
        _uiState.update { it.copy(selectedModel = model) }
        viewModelScope.launch {
            credentialStore.saveGeminiModel(model.id)
        }
    }

    fun attachFile(name: String, content: String, uriString: String? = null) {
        val attached = AttachedFile(name = name, content = content, uriString = uriString)
        _uiState.update { it.copy(attachedFiles = it.attachedFiles + attached) }
    }

    fun removeAttachment(index: Int) {
        _uiState.update { state ->
            val updated = state.attachedFiles.toMutableList().apply {
                if (index in indices) removeAt(index)
            }
            state.copy(attachedFiles = updated)
        }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(attachedFiles = emptyList()) }
    }

    fun showDiffPreview(diff: DiffPreviewBlock) {
        _uiState.update { it.copy(activeDiffForPreview = diff) }
    }

    fun dismissDiffPreview() {
        _uiState.update { it.copy(activeDiffForPreview = null) }
    }

    fun applyDiff(diff: DiffPreviewBlock) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeDiffForPreview = null, isLoading = true) }

            // Find file content if possible
            val readRes = projectManager.readText(diff.filePath)
            val writeRes = if (readRes.isSuccess) {
                val originalText = readRes.getOrNull().orEmpty()
                val updatedText = if (originalText.contains(diff.originalSnippet) && diff.originalSnippet.isNotBlank()) {
                    originalText.replaceFirst(diff.originalSnippet, diff.replacementSnippet)
                } else {
                    diff.replacementSnippet
                }
                projectManager.writeTextAtomic(diff.filePath, updatedText)
            } else {
                projectManager.writeTextAtomic(diff.filePath, diff.replacementSnippet)
            }

            if (writeRes.isSuccess) {
                val confirmMsg = ChatMessage(
                    role = "assistant",
                    text = "✅ Successfully applied modifications to `${diff.filePath}`."
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        messages = it.messages + confirmMsg,
                        statusMessage = "Changes saved to ${diff.filePath}"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to write file: ${writeRes.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() && _uiState.value.attachedFiles.isEmpty()) return

        val currentAttachments = _uiState.value.attachedFiles
        val activeModel = _uiState.value.selectedModel

        val userMsg = ChatMessage(
            role = "user",
            text = userText,
            attachments = currentAttachments
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                attachedFiles = emptyList(),
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            val key = credentialStore.geminiApiKey.firstOrNull() ?: run {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "No Gemini API key set. Please set it in Settings."
                    )
                }
                return@launch
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
            _uiState.update { it.copy(messages = it.messages + assistantMsg) }

            val responseAccumulator = StringBuilder()

            geminiClient.streamGenerate(
                apiKey = key,
                modelId = activeModel.id,
                systemPrompt = systemPrompt,
                userPrompt = fullUserPrompt
            ).catch { e ->
                val errorMsg = "⚠️ Gemini Error: ${e.message ?: "Failed to generate response"}"
                _uiState.update { state ->
                    val msgs = state.messages.toMutableList()
                    if (msgs.isNotEmpty()) {
                        val lastIdx = msgs.lastIndex
                        msgs[lastIdx] = msgs[lastIdx].copy(
                            text = errorMsg,
                            isStreaming = false
                        )
                    }
                    state.copy(
                        messages = msgs,
                        isLoading = false,
                        error = e.message
                    )
                }
            }.collect { token ->
                responseAccumulator.append(token)
                val currentText = responseAccumulator.toString()
                _uiState.update { state ->
                    val msgs = state.messages.toMutableList()
                    if (msgs.isNotEmpty()) {
                        val lastIdx = msgs.lastIndex
                        msgs[lastIdx] = msgs[lastIdx].copy(text = currentText)
                    }
                    state.copy(messages = msgs)
                }
            }

            // Stream completed: finalize message and parse any diff blocks
            val finalText = responseAccumulator.toString().ifBlank {
                if (_uiState.value.error != null) "" else "⚠️ No response received. Please verify your Gemini API key in Settings."
            }
            val parsedDiffs = if (finalText.isNotBlank()) DiffParser.parseAllDiffs(finalText) else emptyList()

            _uiState.update { state ->
                val msgs = state.messages.toMutableList()
                if (msgs.isNotEmpty()) {
                    val lastIdx = msgs.lastIndex
                    val existingText = msgs[lastIdx].text
                    val textToDisplay = if (existingText.startsWith("⚠️")) existingText else finalText
                    msgs[lastIdx] = msgs[lastIdx].copy(
                        text = textToDisplay,
                        diffBlocks = parsedDiffs,
                        isStreaming = false
                    )
                }
                state.copy(
                    messages = msgs,
                    isLoading = false
                )
            }
        }
    }
}
