package com.aro.code.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.filesystem.LocalFileSystemEngine
import com.aro.code.filesystem.SafProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val currentPath: String? = null,
    val currentContent: String? = null,
    val isLoading: Boolean = false,
    val isDirty: Boolean = false,
    val statusMessage: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    val bridge: EditorBridge,
    private val fs: LocalFileSystemEngine,
    private val safManager: SafProjectManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    private var originalContent: String? = null

    init {
        viewModelScope.launch {
            bridge.events.collect { event ->
                when (event.type) {
                    "content_changed" -> {
                        event.payload?.let { rawPayload ->
                            // Defensive decoding: extract content if payload was JSON encoded
                            val cleanText = if (rawPayload.startsWith("{") && rawPayload.contains("\"content\":")) {
                                runCatching {
                                    val obj = org.json.JSONObject(rawPayload)
                                    obj.optString("content", rawPayload)
                                }.getOrDefault(rawPayload)
                            } else {
                                rawPayload
                            }
                            updateContent(cleanText)
                        }
                    }
                    "prompt" -> { /* forwarded to AI module */ }
                }
            }
        }
    }

    fun loadFile(pathOrUri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentPath = pathOrUri) }
            safManager.readText(pathOrUri)
                .onSuccess { content ->
                    originalContent = content
                    _uiState.update {
                        it.copy(
                            currentContent = content,
                            isDirty = false,
                            isLoading = false,
                            statusMessage = "Loaded"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Failed to open file: ${e.message}"
                        )
                    }
                }
        }
    }

    fun updateContent(newContent: String) {
        val dirty = (newContent != originalContent)
        _uiState.update { it.copy(currentContent = newContent, isDirty = dirty) }
    }

    fun saveFile() {
        val targetPath = _uiState.value.currentPath ?: return
        val content = _uiState.value.currentContent ?: return
        viewModelScope.launch {
            safManager.writeTextAtomic(targetPath, content)
                .onSuccess {
                    originalContent = content
                    _uiState.update { it.copy(isDirty = false, statusMessage = "Saved ✓") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(statusMessage = "Save failed: ${e.message}") }
                }
        }
    }

    fun requestDiff(filePath: String) {
        bridge.onDiffRequested(filePath)
    }
}
