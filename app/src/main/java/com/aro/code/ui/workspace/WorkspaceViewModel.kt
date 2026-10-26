package com.aro.code.ui.workspace

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aro.code.ai.GeminiModel
import com.aro.code.auth.AuthRepository
import com.aro.code.auth.CredentialStore
import com.aro.code.cloudbuild.BuildType
import com.aro.code.cloudbuild.CloudBuildPipeline
import com.aro.code.filesystem.LocalFileSystemEngine
import com.aro.code.filesystem.ProjectNode
import com.aro.code.filesystem.SafProjectManager
import com.aro.code.vcs.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GitStatusInfo(
    val branch: String = "main",
    val changedFiles: List<String> = emptyList(),
    val isGitRepo: Boolean = false
)

data class WorkspaceUiState(
    val projectNodes: List<ProjectNode> = emptyList(),
    val projectName: String? = null,
    val projectUriString: String? = null,
    val isGoogleSignedIn: Boolean = false,
    val googleTokenSnippet: String? = null,
    val selectedModel: GeminiModel = GeminiModel.FLASH_3_8,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val showCloneDialog: Boolean = false,
    val showAntiGravityDialog: Boolean = false,
    val showGitDialog: Boolean = false,
    val gitStatus: GitStatusInfo? = null
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val fs: LocalFileSystemEngine,
    private val safManager: SafProjectManager,
    private val git: GitRepository,
    private val buildPipeline: CloudBuildPipeline,
    private val credentialStore: CredentialStore,
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private var pendingCodeVerifier: String = ""
    private val expandedPaths = mutableSetOf<String>()

    init {
        // Module 1.2 & TASK-201 — Silent token refresh on app boot
        viewModelScope.launch {
            authRepo.refreshGoogleTokensIfNeeded()
        }

        // Monitor selected Gemini model
        viewModelScope.launch {
            credentialStore.geminiModel.collect { modelId ->
                val model = GeminiModel.fromId(modelId)
                _uiState.update { it.copy(selectedModel = model) }
            }
        }

        // Monitor Google Auth state (Display redacted snippet to avoid token leak in public UI state)
        viewModelScope.launch {
            credentialStore.googleAccessToken.collect { token ->
                val snippet = token?.takeIf { it.isNotBlank() }?.let { t ->
                    if (t.length > 12) "${t.take(6)}...${t.takeLast(4)}" else t
                }
                _uiState.update {
                    it.copy(
                        isGoogleSignedIn = !token.isNullOrBlank(),
                        googleTokenSnippet = snippet
                    )
                }
            }
        }

        // Monitor Project Directory URI
        viewModelScope.launch {
            credentialStore.projectDirUri.collect { uriStr ->
                _uiState.update { it.copy(projectUriString = uriStr) }
                safManager.getPhysicalPath(uriStr)?.let { path ->
                    com.aro.code.terminal.BashShellSession.setWorkingDirectory(path)
                }
                refreshFileTree()
            }
        }
    }

    private var cachedRawNodes: List<ProjectNode> = emptyList()

    fun refreshFileTree() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val uriStr = _uiState.value.projectUriString

            val rawNodes = if (!uriStr.isNullOrBlank()) {
                val uri = Uri.parse(uriStr)
                _uiState.update { it.copy(projectName = uri.lastPathSegment?.substringAfterLast(':') ?: "SAF Project") }
                safManager.scanSafTree(uri)
            } else {
                _uiState.update { it.copy(projectName = "Workspace (Internal)") }
                safManager.scanLocalDirectory(fs.reposRoot)
            }

            cachedRawNodes = rawNodes

            // Apply expanded state
            val flattened = applyExpandedState(rawNodes)
            _uiState.update { it.copy(projectNodes = flattened, isLoading = false) }
        }
    }

    private fun applyExpandedState(nodes: List<ProjectNode>): List<ProjectNode> {
        val result = mutableListOf<ProjectNode>()
        for (node in nodes) {
            val isExpanded = expandedPaths.contains(node.path)
            result.add(node.copy(isExpanded = isExpanded))
            if (node.isDirectory && isExpanded && node.children.isNotEmpty()) {
                result.addAll(applyExpandedState(node.children))
            }
        }
        return result
    }

    fun toggleDirectory(node: ProjectNode) {
        if (!node.isDirectory) return
        if (expandedPaths.contains(node.path)) {
            expandedPaths.remove(node.path)
        } else {
            expandedPaths.add(node.path)
        }
        // Instant in-memory toggle without redundant physical disk re-scans
        if (cachedRawNodes.isNotEmpty()) {
            val flattened = applyExpandedState(cachedRawNodes)
            _uiState.update { it.copy(projectNodes = flattened) }
        } else {
            refreshFileTree()
        }
    }

    fun onProjectFolderSelected(uri: Uri) {
        viewModelScope.launch {
            safManager.takePersistableUriPermission(uri)
            credentialStore.saveProjectDirUri(uri.toString())
            safManager.getPhysicalPath(uri.toString())?.let { physicalPath ->
                com.aro.code.terminal.BashShellSession.setWorkingDirectory(physicalPath)
            }
            _uiState.update {
                it.copy(
                    projectUriString = uri.toString(),
                    statusMessage = "Opened folder: ${uri.lastPathSegment}"
                )
            }
            refreshFileTree()
        }
    }

    fun resetToInternalStorage() {
        viewModelScope.launch {
            credentialStore.saveProjectDirUri(null)
            expandedPaths.clear()
            _uiState.update {
                it.copy(
                    projectUriString = null,
                    statusMessage = "Switched to internal workspace"
                )
            }
            refreshFileTree()
        }
    }

    fun selectModel(model: GeminiModel) {
        viewModelScope.launch {
            credentialStore.saveGeminiModel(model.id)
            _uiState.update { it.copy(selectedModel = model) }
        }
    }

    // ── Anti-Gravity Google Auth ─────────────────────────────────────────────

    fun openAntiGravityDialog() = _uiState.update { it.copy(showAntiGravityDialog = true) }
    fun dismissAntiGravityDialog() = _uiState.update { it.copy(showAntiGravityDialog = false) }

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
                        showAntiGravityDialog = false,
                        isLoading = false,
                        statusMessage = "Anti-Gravity Google login successful ✓"
                    )
                }
                onResult(Result.success(Unit))
            } else {
                val err = result.exceptionOrNull() ?: Exception("Invalid code")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Google login failed: ${err.message}"
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
                    showAntiGravityDialog = false,
                    statusMessage = "Signed out of Google Anti-Gravity"
                )
            }
        }
    }

    // ── Git & Cloud Build ───────────────────────────────────────────────────

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

    fun openGitDialog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projectPath = safManager.getPhysicalPath(_uiState.value.projectUriString)
            val repoDir = if (projectPath != null) File(projectPath) else fs.reposRoot
            val gitDir = File(repoDir, ".git")

            if (!gitDir.exists()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showGitDialog = true,
                        gitStatus = GitStatusInfo(isGitRepo = false)
                    )
                }
                return@launch
            }

            var branchName = "main"
            runCatching {
                org.eclipse.jgit.api.Git.open(repoDir).use { g ->
                    branchName = g.repository.branch ?: "main"
                }
            }

            val statusResult = git.status(repoDir)
            val changedFiles = statusResult.getOrDefault(emptySet()).toList()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    showGitDialog = true,
                    gitStatus = GitStatusInfo(
                        branch = branchName,
                        changedFiles = changedFiles,
                        isGitRepo = true
                    )
                )
            }
        }
    }

    fun dismissGitDialog() {
        _uiState.update { it.copy(showGitDialog = false) }
    }

    fun commitAndPush(commitMessage: String) {
        if (commitMessage.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Committing changes…") }
            val projectPath = safManager.getPhysicalPath(_uiState.value.projectUriString)
            val repoDir = if (projectPath != null) File(projectPath) else fs.reposRoot
            val token = credentialStore.githubToken.firstOrNull() ?: ""

            git.stageAndCommit(
                repoDir = repoDir,
                message = commitMessage,
                authorName = "Aro Code Developer",
                authorEmail = "developer@arocode.app"
            ).onSuccess { commitId ->
                if (token.isNotBlank()) {
                    _uiState.update { it.copy(statusMessage = "Pushing to remote…") }
                    git.push(repoDir, token)
                        .onSuccess {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    showGitDialog = false,
                                    statusMessage = "Committed & Pushed ($commitId) ✓"
                                )
                            }
                            refreshFileTree()
                        }
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    statusMessage = "Committed ($commitId), push failed: ${e.message}"
                                )
                            }
                        }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showGitDialog = false,
                            statusMessage = "Committed ($commitId) ✓ (Configure GitHub token in Settings to push)"
                        )
                    }
                    refreshFileTree()
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Commit failed: ${e.message}"
                    )
                }
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
