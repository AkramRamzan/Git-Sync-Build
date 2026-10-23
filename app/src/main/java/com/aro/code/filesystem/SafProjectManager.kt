package com.aro.code.filesystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectNode(
    val name: String,
    val uri: Uri? = null,
    val file: File? = null,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val isExpanded: Boolean = false,
    val level: Int = 0,
    val children: List<ProjectNode> = emptyList()
)

/**
 * Module 2 – Storage Access Framework (SAF) Project Manager.
 * Handles directory picking, persistent permissions, async tree scanning,
 * and atomic read/write operations for project files.
 */
@Singleton
class SafProjectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localFs: LocalFileSystemEngine
) {
    /**
     * Persist read & write permissions across app restarts.
     */
    fun takePersistableUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Already persisted or not supported by provider
        }
    }

    /**
     * Asynchronously scan a SAF tree URI into a list of project nodes.
     */
    suspend fun scanSafTree(treeUri: Uri, maxDepth: Int = 4): List<ProjectNode> =
        withContext(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            scanDocumentDirectory(rootDoc, level = 0, currentPath = rootDoc.name ?: "Project", maxDepth = maxDepth)
        }

    private fun scanDocumentDirectory(
        dir: DocumentFile,
        level: Int,
        currentPath: String,
        maxDepth: Int
    ): List<ProjectNode> {
        if (level > maxDepth) return emptyList()
        val ignored = setOf(".git", ".gradle", "build", ".idea", ".DS_Store", "node_modules")
        val files = dir.listFiles()
        val nodes = mutableListOf<ProjectNode>()

        // Directories first, then files sorted by name
        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))

        for (item in sorted) {
            val name = item.name ?: continue
            if (name in ignored) continue

            val itemPath = "$currentPath/$name"
            if (item.isDirectory) {
                val childNodes = scanDocumentDirectory(item, level + 1, itemPath, maxDepth)
                nodes.add(
                    ProjectNode(
                        name = name,
                        uri = item.uri,
                        path = itemPath,
                        isDirectory = true,
                        level = level,
                        children = childNodes
                    )
                )
            } else {
                nodes.add(
                    ProjectNode(
                        name = name,
                        uri = item.uri,
                        path = itemPath,
                        isDirectory = false,
                        sizeBytes = item.length(),
                        level = level
                    )
                )
            }
        }
        return nodes
    }

    /**
     * Asynchronously scan a local POSIX File directory into project nodes.
     */
    suspend fun scanLocalDirectory(root: File, maxDepth: Int = 4): List<ProjectNode> =
        withContext(Dispatchers.IO) {
            scanLocalFileDirectory(root, level = 0, currentPath = root.name, maxDepth = maxDepth)
        }

    private fun scanLocalFileDirectory(
        dir: File,
        level: Int,
        currentPath: String,
        maxDepth: Int
    ): List<ProjectNode> {
        if (level > maxDepth) return emptyList()
        val ignored = setOf(".git", ".gradle", "build", ".idea", ".DS_Store", "node_modules")
        val files = dir.listFiles() ?: return emptyList()
        val nodes = mutableListOf<ProjectNode>()

        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        for (item in sorted) {
            val name = item.name
            if (name in ignored) continue

            val itemPath = "$currentPath/$name"
            if (item.isDirectory) {
                val childNodes = scanLocalFileDirectory(item, level + 1, itemPath, maxDepth)
                nodes.add(
                    ProjectNode(
                        name = name,
                        file = item,
                        path = itemPath,
                        isDirectory = true,
                        level = level,
                        children = childNodes
                    )
                )
            } else {
                nodes.add(
                    ProjectNode(
                        name = name,
                        file = item,
                        path = itemPath,
                        isDirectory = false,
                        sizeBytes = item.length(),
                        level = level
                    )
                )
            }
        }
        return nodes
    }

    /**
     * Read text from either a SAF Uri or a local File.
     */
    suspend fun readText(uriStringOrPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (uriStringOrPath.startsWith("content://")) {
                val uri = Uri.parse(uriStringOrPath)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Failed to open content stream")
            } else {
                val file = File(uriStringOrPath)
                file.readText(Charsets.UTF_8)
            }
        }
    }

    /**
     * Atomically write text to either a SAF Uri or a local File.
     */
    suspend fun writeTextAtomic(uriStringOrPath: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (uriStringOrPath.startsWith("content://")) {
                    val uri = Uri.parse(uriStringOrPath)
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("Failed to open output stream for writing")
                } else {
                    val file = File(uriStringOrPath)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parent, ".tmp_${file.name}")
                    tmp.writeText(content, Charsets.UTF_8)
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                Unit
            }
        }
}
