package com.aro.code.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.auth.CredentialStore
import com.aro.code.build.BuildType
import com.aro.code.build.CloudBuildPipeline
import com.aro.code.filesystem.LocalFileSystemEngine
import com.aro.code.vcs.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class WorkspaceUiState(
    val files: List<File>      = emptyList(),
    val isLoading: Boolean     = false,
    val statusMessage: String? = null,
    val showCloneDialog: Boolean = false
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val fs: LocalFileSystemEngine,
    private val git: GitRepository,
    private val buildPipeline: CloudBuildPipeline,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init { refreshFileTree() }

    fun refreshFileTree() {
        viewModelScope.launch {
            val files = fs.listSourceFiles(fs.reposRoot)
            _uiState.update { it.copy(files = files) }
        }
    }

    fun clonePrompt() = _uiState.update { it.copy(showCloneDialog = true) }
    fun dismissCloneDialog() = _uiState.update { it.copy(showCloneDialog = false) }

    fun cloneRepo(remoteUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showCloneDialog = false) }
            val token = credentialStore.githubToken.firstOrNull() ?: ""
            val repoName = remoteUrl.substringAfterLast('/').removeSuffix(".git")
            git.clone(remoteUrl, repoName, token).onSuccess {
                _uiState.update { it.copy(isLoading = false, statusMessage = "Cloned: $repoName") }
                refreshFileTree()
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, statusMessage = "Clone failed: ${e.message}") }
            }
        }
    }

    fun triggerCloudBuild() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Triggering cloud build…") }
            val token = credentialStore.githubToken.firstOrNull() ?: return@launch
            buildPipeline.triggerBuild("AkramRamzan", "Git-Sync-Build", token, BuildType.DEBUG)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Build triggered ✓") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Build trigger failed: ${e.message}") }
                }
        }
    }
}
