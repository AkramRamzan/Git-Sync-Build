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
    val statusMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
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
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastSnapshotTime = 0L

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
                    undoStack.clear()
                    redoStack.clear()
                    _uiState.update {
                        it.copy(
                            currentContent = content,
                            isDirty = false,
                            isLoading = false,
                            statusMessage = "Loaded",
                            canUndo = false,
                            canRedo = false
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

    fun updateContent(newContent: String, isUndoRedo: Boolean = false) {
        val current = _uiState.value.currentContent
        if (!isUndoRedo && current != null && current != newContent) {
            val now = System.currentTimeMillis()
            // Snapshot to undo stack if last snapshot was > 600ms ago or on first change
            if (now - lastSnapshotTime > 600L || undoStack.isEmpty()) {
                if (undoStack.size >= 50) undoStack.removeFirst()
                undoStack.addLast(current)
                lastSnapshotTime = now
                redoStack.clear()
            }
        }
        val dirty = (newContent != originalContent)
        _uiState.update {
            it.copy(
                currentContent = newContent,
                isDirty = dirty,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _uiState.value.currentContent ?: return
        val prev = undoStack.removeLast()
        redoStack.addLast(current)
        val dirty = (prev != originalContent)
        _uiState.update {
            it.copy(
                currentContent = prev,
                isDirty = dirty,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
                statusMessage = "Undo"
            )
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _uiState.value.currentContent ?: return
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        val dirty = (next != originalContent)
        _uiState.update {
            it.copy(
                currentContent = next,
                isDirty = dirty,
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
                statusMessage = "Redo"
            )
        }
    }

    fun replaceText(search: String, replace: String, replaceAll: Boolean = false) {
        val content = _uiState.value.currentContent ?: return
        if (search.isEmpty()) return
        val updated = if (replaceAll) {
            content.replace(search, replace)
        } else {
            content.replaceFirst(search, replace)
        }
        if (updated != content) {
            undoStack.addLast(content)
            redoStack.clear()
            val dirty = (updated != originalContent)
            _uiState.update {
                it.copy(
                    currentContent = updated,
                    isDirty = dirty,
                    canUndo = true,
                    canRedo = false,
                    statusMessage = if (replaceAll) "Replaced all occurrences" else "Replaced match"
                )
            }
        }
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
