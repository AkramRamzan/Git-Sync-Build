package com.aro.code.filesystem

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L
const val MAX_REPO_SIZE_BYTES = 500 * 1024 * 1024L

/**
 * Module 1 – Local POSIX File System Engine.
 * All repos live under context.filesDir/repos/<name> for direct POSIX access.
 */
@Singleton
class LocalFileSystemEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val reposRoot: File get() = File(context.filesDir, "repos").also { it.mkdirs() }
    fun repoDir(repoName: String): File = File(reposRoot, repoName)

    suspend fun readFile(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.length() <= MAX_FILE_SIZE_BYTES) { "File too large: ${file.name}" }
            file.readText(Charsets.UTF_8)
        }
    }

    suspend fun writeFileAtomic(file: File, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parent, ".tmp_${file.name}")
                tmp.writeText(content, Charsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                Unit
            }
        }

    suspend fun listSourceFiles(
        root: File,
        ignoredPatterns: Set<String> = setOf(".git", "build", ".gradle")
    ): List<File> = withContext(Dispatchers.IO) {
        root.walkTopDown()
            .filter { it.isFile }
            .filter { f -> ignoredPatterns.none { f.path.contains(it) } }
            .filter { it.length() <= MAX_FILE_SIZE_BYTES }
            .toList()
    }

    suspend fun delete(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            file.deleteRecursively()
            Unit
        }
    }

    fun posixPath(file: File): Path = file.toPath()
}
