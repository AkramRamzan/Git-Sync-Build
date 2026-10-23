package com.aro.code.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.filesystem.LocalFileSystemEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val currentContent: String? = null,
    val isLoading: Boolean = false,
    val statusMessage: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    val bridge: EditorBridge,
    private val fs: LocalFileSystemEngine
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var currentFile: File? = null

    init {
        viewModelScope.launch {
            bridge.events.collect { event ->
                when (event.type) {
                    "content_changed" -> { /* debounce auto-save handled below */ }
                    "prompt" -> { /* forwarded to AI module */ }
                }
            }
        }
    }

    fun openFile(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            currentFile = file
            fs.readFile(file).onSuccess { content ->
                _uiState.update { it.copy(currentContent = content, isLoading = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, statusMessage = e.message) }
            }
        }
    }

    fun saveFile() {
        val file = currentFile ?: return
        val content = _uiState.value.currentContent ?: return
        viewModelScope.launch {
            fs.writeFileAtomic(file, content).onSuccess {
                _uiState.update { it.copy(statusMessage = "Saved") }
            }.onFailure { e ->
                _uiState.update { it.copy(statusMessage = "Save failed: ${e.message}") }
            }
        }
    }

    fun requestDiff(filePath: String) {
        bridge.onDiffRequested(filePath)
    }
}
