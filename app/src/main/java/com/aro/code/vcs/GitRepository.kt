package com.aro.code.vcs

import com.aro.code.filesystem.LocalFileSystemEngine
import com.aro.code.filesystem.MAX_REPO_SIZE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 3 – Embedded Git Engine (JGit).
 * All operations run on Dispatchers.IO with coroutine cancellation support.
 */
@Singleton
class GitRepository @Inject constructor(
    private val fs: LocalFileSystemEngine
) {
    // ── Clone ───────────────────────────────────────────────────────────────
    suspend fun clone(
        remoteUrl: String,
        repoName: String,
        token: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = fs.repoDir(repoName)
            require(!dest.exists()) { "Repository already cloned: $repoName" }
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(dest)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider("oauth2", token))
                .setProgressMonitor(object : ProgressMonitor {
                    override fun start(totalTasks: Int) {}
                    override fun beginTask(title: String, totalWork: Int) {}
                    override fun update(completed: Int) { onProgress(completed) }
                    override fun endTask() {}
                    override fun isCancelled(): Boolean = false
                    override fun showDuration(enabled: Boolean) {}
                })
                .call()
                .close()
            dest
        }
    }

    // ── Stage & Commit ──────────────────────────────────────────────────────
    suspend fun stageAndCommit(
        repoDir: File,
        message: String,
        authorName: String,
        authorEmail: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                git.add().addFilepattern(".").call()
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .call()
                commit.name
            }
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────
    suspend fun push(repoDir: File, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Git.open(repoDir).use { git ->
                    git.push()
                        .setCredentialsProvider(
                            UsernamePasswordCredentialsProvider("oauth2", token)
                        )
                        .call()
                }
                Unit
            }
        }

    // ── Stash (pre-patch checkpoint) ────────────────────────────────────────
    suspend fun stash(repoDir: File): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                val commit = git.stashCreate().call()
                commit != null
            }
        }
    }

    /** Pops the latest stash to revert a failed patch. */
    suspend fun stashPop(repoDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                git.stashApply().call()
                git.stashDrop().call()
            }
            Unit
        }
    }

    // ── Branch ──────────────────────────────────────────────────────────────
    suspend fun createBranch(repoDir: File, branchName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Git.open(repoDir).use { git ->
                    git.checkout().setCreateBranch(true).setName(branchName).call()
                }
                Unit
            }
        }

    // ── Status ──────────────────────────────────────────────────────
    suspend fun status(repoDir: File): Result<Set<String>> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                val s = git.status().call()
                s.modified + s.added + s.removed + s.untracked
            }
        }
    }
}
