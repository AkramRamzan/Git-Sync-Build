package com.aro.code.ai

import com.aro.code.filesystem.LocalFileSystemEngine
import com.aro.code.vcs.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class PatchResult {
    data class Success(val commitSha: String) : PatchResult()
    data class ValidationFailed(val errors: String) : PatchResult()
    data class ApplyFailed(val reason: String) : PatchResult()
}

/**
 * Module 5 – AI Patch Orchestrator.
 * Coordinates: stash → parse blocks → apply → validate → commit, or revert.
 */
@Singleton
class AiPatchOrchestrator @Inject constructor(
    private val diffEngine: AiDiffEngine,
    private val validator: SyntaxValidator,
    private val fs: LocalFileSystemEngine,
    private val git: GitRepository
) {
    suspend fun applyModelResponse(
        repoDir: File,
        targetFile: File,
        modelResponse: String,
        language: String,
        token: String,
        authorName: String,
        authorEmail: String
    ): PatchResult = withContext(Dispatchers.IO) {
        // 1. Stash as rollback checkpoint (if working tree has dirty files)
        val stashCreated = git.stash(repoDir).getOrDefault(false)

        suspend fun rollback() {
            if (stashCreated) {
                git.stashPop(repoDir)
            }
        }

        // 2. Read original source
        val original = fs.readFile(targetFile).getOrElse {
            rollback()
            return@withContext PatchResult.ApplyFailed("Could not read file: ${it.message}")
        }

        // 3. Parse SEARCH/REPLACE blocks
        val blocks = diffEngine.parseBlocks(modelResponse)
        if (blocks.isEmpty()) {
            rollback()
            return@withContext PatchResult.ApplyFailed("No SEARCH/REPLACE blocks found in response")
        }

        // 4. Apply patches
        val patched = diffEngine.applyPatches(original, blocks).getOrElse {
            rollback()
            return@withContext PatchResult.ApplyFailed(it.message ?: "Patch apply error")
        }

        // 5. Syntax validation (Tree-Sitter)
        val valid = validator.validate(patched, language)
        if (!valid) {
            rollback()
            return@withContext PatchResult.ValidationFailed("AST validation failed post-patch")
        }

        // 6. Write atomically
        fs.writeFileAtomic(targetFile, patched).getOrElse {
            rollback()
            return@withContext PatchResult.ApplyFailed("Write failed: ${it.message}")
        }

        // 7. Commit
        val sha = git.stageAndCommit(
            repoDir, "ai: apply model patch to ${targetFile.name}",
            authorName, authorEmail
        ).getOrElse {
            rollback()
            return@withContext PatchResult.ApplyFailed("Commit failed: ${it.message}")
        }

        PatchResult.Success(sha)
    }
}
