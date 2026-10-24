package com.aro.code.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.ai.AntiGravityBridgeClient
import com.aro.code.ai.AntiGravityModel
import com.aro.code.ai.DiffParser
import com.aro.code.ai.DiffPreviewBlock
import com.aro.code.ai.GeminiClient
import com.aro.code.ai.GeminiModel
import com.aro.code.auth.AuthRepository
import com.aro.code.auth.CredentialStore
import com.aro.code.db.ChatHistoryDatabase
import com.aro.code.filesystem.ProjectNode
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
    val selectedModel: GeminiModel = GeminiModel.FLASH_3_8,
    val selectedAntiGravityModel: AntiGravityModel = AntiGravityModel.FLASH_3_8,
    val attachedFiles: List<AttachedFile> = emptyList(),
    val activeProjectName: String? = null,
    val activeProjectUri: String? = null,
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
    private val bridgeClient: AntiGravityBridgeClient,
    private val credentialStore: CredentialStore,
    private val projectManager: SafProjectManager,
    private val chatDb: ChatHistoryDatabase,
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var lastBridgeCheckTime = 0L
    private var cachedBridgeAlive = false

    private suspend fun isBridgeAliveCached(bridgeUrl: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBridgeCheckTime < 30_000L) {
            return cachedBridgeAlive
        }
        val alive = bridgeClient.isBridgeAlive(bridgeUrl)
        cachedBridgeAlive = alive
        lastBridgeCheckTime = now
        return alive
    }

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
        // Monitor Project Directory URI for Active Project Badge & Summary Mode
        viewModelScope.launch {
            credentialStore.projectDirUri.collect { uriStr ->
                val name = if (!uriStr.isNullOrBlank()) {
                    val uri = Uri.parse(uriStr)
                    val segment = uri.lastPathSegment ?: ""
                    segment.substringAfterLast('/').substringAfterLast(':').ifBlank { "Project" }
                } else {
                    null
                }
                _uiState.update { it.copy(activeProjectName = name, activeProjectUri = uriStr) }
            }
        }

        // Monitor Anti-Gravity OAuth sign-in state
        viewModelScope.launch {
            credentialStore.googleAccessToken.collect { token ->
                val snippet = token?.takeIf { it.isNotBlank() }?.let { t ->
                    if (t.length > 12) "${t.take(6)}...${t.takeLast(4)}" else t
                }
                _uiState.update {
                    it.copy(
                        isAntiGravitySignedIn = !token.isNullOrBlank(),
                        antiGravityTokenSnippet = snippet
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

    fun attachMultipleFiles(files: List<AttachedFile>) {
        _uiState.update { it.copy(attachedFiles = it.attachedFiles + files) }
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

            val isAntiGravity = _uiState.value.engine == ChatEngine.ANTIGRAVITY
            if (writeRes.isSuccess) {
                val confirmMsg = ChatMessage(
                    role = "model",
                    text = "✅ Successfully applied modifications to `${diff.filePath}`."
                )
                appendMessage(confirmMsg, isAntiGravity)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Applied changes to ${diff.filePath} ✓"
                    )
                }
            } else {
                val errMsg = "Failed to apply diff to ${diff.filePath}: ${writeRes.exceptionOrNull()?.message}"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errMsg
                    )
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

    fun selectActiveProject(uri: Uri) {
        viewModelScope.launch {
            projectManager.takePersistableUriPermission(uri)
            credentialStore.saveProjectDirUri(uri.toString())
        }
    }

    fun send(userText: String) {
        val trimmed = userText.trim()
        val isUnderstandCmd = trimmed.equals("understand project", ignoreCase = true) ||
                              trimmed.equals("understand this project", ignoreCase = true) ||
                              trimmed.equals("analyze project", ignoreCase = true) ||
                              trimmed.equals("project summary", ignoreCase = true) ||
                              trimmed.equals("summarize project", ignoreCase = true)

        if (isUnderstandCmd && _uiState.value.activeProjectUri != null) {
            generateProjectSummary()
            return
        }

        sendChatMessage(userText)
    }

    fun generateProjectSummary() {
        viewModelScope.launch {
            val uriStr = _uiState.value.activeProjectUri
            val projectName = _uiState.value.activeProjectName ?: "Current Project"
            _uiState.update { it.copy(isLoading = true, activeStep = "📁 [1/3] Scanning project file tree ($projectName)...") }

            val tree = mutableListOf<String>()
            fun collectPaths(nodes: List<ProjectNode>) {
                for (node in nodes) {
                    if (node.isDirectory) {
                        collectPaths(node.children)
                    } else {
                        tree.add(node.path)
                    }
                }
            }

            try {
                val nodes = if (!uriStr.isNullOrBlank()) {
                    projectManager.scanSafTree(Uri.parse(uriStr), maxDepth = 6)
                } else {
                    emptyList()
                }
                collectPaths(nodes)

                if (tree.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeStep = null,
                            error = "No files found in project. Please open a project folder in Workspace first."
                        )
                    }
                    return@launch
                }

                val summaryPrompt = buildString {
                    appendLine("=== PROJECT SUMMARY MODE (PRO) ===")
                    appendLine("Project Name: $projectName")
                    appendLine("Total Files: ${tree.size}")
                    appendLine("Full File Structure:")
                    tree.take(120).forEachIndexed { index, path ->
                        appendLine("  ${index + 1}. $path")
                    }
                    if (tree.size > 120) {
                        appendLine("  ... [and ${tree.size - 120} more files]")
                    }
                    appendLine()
                    appendLine("INSTRUCTION:")
                    appendLine("Understand this project based on the complete file structure above.")
                    appendLine("1. Give a high-level architectural overview of this project (what type of app/system it is, core modules, tech stack).")
                    appendLine("2. List the key entry points and critical source files.")
                    appendLine("3. Ask me which specific files or features I would like to inspect, modify, or debug in detail.")
                }

                sendChatMessage(summaryPrompt)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, activeStep = null, error = "Failed to scan project: ${e.message}") }
            }
        }
    }

    private fun sendChatMessage(userText: String) {
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

            val apiKey = credentialStore.geminiApiKey.firstOrNull()?.takeIf { it.isNotBlank() }
            val bridgeUrl = com.aro.code.terminal.TermuxBridgeService.state.activeUrl.value.takeIf { it.isNotBlank() }
                ?: credentialStore.antigravityBridgeUrl.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "http://127.0.0.1:8000"
            val bridgeToken = credentialStore.antigravityBridgeToken.firstOrNull()?.takeIf { it.isNotBlank() }

            val useBridge: Boolean
            val authKey: String
            val activeSystemPrompt: String
            val targetModelId: String

            val projectPhysicalPath = projectManager.getPhysicalPath(_uiState.value.activeProjectUri)

            if (isAntiGravity) {
                val selectedAgModel = _uiState.value.selectedAntiGravityModel
                targetModelId = selectedAgModel.id
                activeSystemPrompt = antiGravitySystemPrompt

                val isBridgeRunning = isBridgeAliveCached(bridgeUrl)

                if (selectedAgModel.isExternalBridgeOnly) {
                    useBridge = true
                    authKey = ""
                } else {
                    if (isBridgeRunning || apiKey.isNullOrBlank()) {
                        useBridge = true
                        authKey = ""
                    } else {
                        useBridge = false
                        authKey = apiKey
                    }
                }

                val targetPathDisplay = projectPhysicalPath?.substringAfterLast('/') ?: _uiState.value.activeProjectName ?: "Workspace"
                _uiState.update {
                    it.copy(activeStep = "🧠 [2/3] agy: Autonomous Agent on $targetPathDisplay (${selectedAgModel.displayName.take(15)})...")
                }
            } else {
                useBridge = false
                if (apiKey.isNullOrBlank()) {
                    val promptKeyMsg = "⚠️ Please configure your Gemini API Key in Settings (⚙)."
                    appendMessage(ChatMessage(role = "model", text = promptKeyMsg, isStreaming = false), isAntiGravity = false)
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

            // Construct prompt with active project workspace info and attached file contexts
            val promptBuilder = StringBuilder()
            if (projectPhysicalPath != null) {
                promptBuilder.appendLine("=== ACTIVE PROJECT WORKSPACE ===")
                promptBuilder.appendLine("Path: $projectPhysicalPath")
                promptBuilder.appendLine("You have direct access to inspect, create, and modify files in this directory.\n")
            }
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

            val streamFlow = if (useBridge) {
                bridgeClient.streamGenerate(
                    bridgeUrl = bridgeUrl,
                    bridgeToken = bridgeToken,
                    modelId = targetModelId,
                    systemPrompt = activeSystemPrompt,
                    userPrompt = fullUserPrompt,
                    projectPath = projectPhysicalPath
                )
            } else {
                geminiClient.streamGenerate(
                    apiKey = authKey,
                    modelId = targetModelId,
                    systemPrompt = activeSystemPrompt,
                    userPrompt = fullUserPrompt
                )
            }

            streamFlow.catch { e ->
                val rawMsg = e.message.orEmpty()
                val friendlyMsg = when {
                    rawMsg.contains("insufficient authentication scopes", ignoreCase = true) ->
                        "⚠️ Anti-Gravity Scope Restriction: Google OAuth token lacks generative scope. Please enter your free Gemini API Key in Settings (⚙) to power the reasoning engine."
                    rawMsg.contains("429") || rawMsg.contains("quota", ignoreCase = true) || rawMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                        "⚠️ Quota Exceeded (429): Rate limit reached. Quota resets in ~30 seconds, or you can enter a new free API key in Settings (⚙)."
                    rawMsg.contains("Connection refused", ignoreCase = true) || rawMsg.contains("Failed to connect", ignoreCase = true) ->
                        "⚠️ Termux Bridge Offline: Connection refused at $bridgeUrl. Please run 'python server.py' in Termux."
                    rawMsg.contains("timeout", ignoreCase = true) ->
                        "⚠️ Anti-Gravity Timeout: The task took longer than expected. Make sure to run 'termux-wake-lock' in Termux so Android does not sleep the bridge."
                    else -> {
                        val prefix = if (isAntiGravity) "⚡ Anti-Gravity Agent Error" else "⚠️ Gemini Error"
                        "$prefix: ${e.message ?: "Failed to generate response"}"
                    }
                }
                updateLastMessage(isAntiGravity) { it.copy(text = friendlyMsg, isStreaming = false) }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        activeStep = null,
                        error = friendlyMsg
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
