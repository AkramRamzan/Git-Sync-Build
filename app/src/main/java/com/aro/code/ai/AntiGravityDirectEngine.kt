package com.aro.code.ai

import android.content.Context
import android.util.Log
import com.aro.code.terminal.LinuxSubsystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AntiGravityDirectEngine — Direct native execution engine for Google Anti-Gravity CLI (`agy`).
 *
 * Architecture:
 * - Executes the in-app embedded `agy` binary directly inside PRoot via Unix pipes.
 * - Completely eliminates the need to run `server.py` or use external Termux.
 * - Streams responses chunk-by-chunk in real-time with zero network or buffering delays.
 */
@Singleton
class AntiGravityDirectEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AntiGravityDirect"
        // Regex to strip ANSI terminal escape color codes for clean Android markdown rendering
        private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")
    }

    /**
     * Checks if the embedded PRoot Linux subsystem is installed and ready.
     */
    fun isReady(): Boolean {
        if (!LinuxSubsystem.isInstalled(context)) return false
        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        return File(rootfsDir, "root/.local/bin/agy").exists() ||
               File(rootfsDir, "usr/local/bin/agy").exists() ||
               File(rootfsDir, "usr/bin/agy").exists()
    }

    /**
     * Streams an Anti-Gravity AI prompt directly using the embedded `agy` CLI binary.
     * Output is streamed chunk-by-chunk directly into the GUI without buffering.
     */
    fun streamPrompt(
        prompt: String,
        modelId: String = "gemini-3.8-flash",
        systemPrompt: String = "",
        projectPath: String? = null
    ): Flow<String> = flow {
        if (!LinuxSubsystem.isInstalled(context)) {
            emit("⚠️ In-app Ubuntu Linux is not installed yet. Please open Terminal to install it.")
            return@flow
        }

        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        val proot = LinuxSubsystem.getProotBinary(context)
        if (proot == null) {
            emit("⚠️ PRoot engine binary (libproot.so) not found.")
            return@flow
        }

        // Exact agy internal CLI model identifiers
        val agyModelName = when (modelId) {
            "gemini-3.8-flash"  -> "gemini-3.8-flash-high"
            "claude-sonnet-4.6" -> "claude-sonnet-4-6"
            "claude-opus-4.6"   -> "claude-opus-4-6-thinking"
            "gpt-oss-120b"      -> "gpt-oss-120b-medium"
            "gemini-4-argon"    -> "gemini-3.8-flash-high"
            else                -> modelId
        }

        // Map Android physical path to guest container path if needed
        val guestCwd = if (!projectPath.isNullOrBlank()) {
            when {
                projectPath.startsWith("/storage/emulated/0") ->
                    projectPath.replaceFirst("/storage/emulated/0", "/storage")
                projectPath.startsWith("/sdcard") ->
                    projectPath.replaceFirst("/sdcard", "/storage")
                else -> "/root"
            }
        } else {
            "/root"
        }

        // Combine system prompt and user prompt
        val fullPrompt = if (systemPrompt.isNotBlank()) {
            "$systemPrompt\n\n$prompt"
        } else {
            prompt
        }

        // Bind host directories into PRoot
        val bindings = mutableListOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys"
        )
        val extStorage = File("/storage/emulated/0")
        if (extStorage.exists()) {
            bindings.add("-b")
            bindings.add("/storage/emulated/0:/storage")
            val download = File("/storage/emulated/0/Download")
            if (download.exists()) {
                bindings.add("-b")
                bindings.add("/storage/emulated/0/Download:/root/Download")
            }
        }

        val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

        val cmd = mutableListOf(
            proot.absolutePath,
            "-v", "-1",
            "-r", rootfsDir.absolutePath,
            "-0",
            "--link2symlink",
            "-L",
            "--sysvipc",
            "-k", "6.17.0-PRoot-Distro",
            "--kill-on-exit"
        )
        cmd.addAll(bindings)

        // Build agy command line inside PRoot
        val addDirArg = if (guestCwd != "/root" && File(rootfsDir, guestCwd.trimStart('/')).exists()) {
            "--add-dir '$guestCwd'"
        } else {
            ""
        }

        // Run unbuffered agy via bash reading text input from stdin
        val shellCmd = "exec /usr/local/bin/agy --model $agyModelName --dangerously-skip-permissions $addDirArg --input-format text"

        cmd.addAll(listOf(
            "-w", guestCwd,
            "/bin/bash", "-c", shellCmd
        ))

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true) // Merge stdout and stderr for immediate streaming

            pb.environment().apply {
                put("PROOT_VERBOSE", "-1")
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_IGNORE_MISSING_BINDINGS", "1")
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                put("PROOT_TMP_DIR", prootTmp.absolutePath)
                put("TMPDIR", prootTmp.absolutePath)
                put("TEMP", prootTmp.absolutePath)
                put("TMP", prootTmp.absolutePath)
                put("TERM", "xterm-256color")
                put("HOME", "/root")
                put("USER", "root")
                put("PATH", "/usr/local/bin:/usr/bin:/root/.local/bin:/bin:/sbin:/usr/sbin")
                put("LANG", "C.UTF-8")
                put("PYTHONUNBUFFERED", "1")
                put("PYTHONIOENCODING", "utf-8")

                remove("ANDROID_DATA")
                remove("ANDROID_ROOT")
                remove("ANDROID_STORAGE")
                remove("ANDROID_ART_ROOT")
                remove("ANDROID_I18N_ROOT")
                remove("ANDROID_TZDATA_ROOT")
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                remove("PROOT_LOADER")
                remove("PROOT_LOADER_32")
            }

            process = withContext(Dispatchers.IO) { pb.start() }

            // Feed full prompt into process stdin in background
            val stdinWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                stdinWriter.write(fullPrompt)
                stdinWriter.flush()
                stdinWriter.close() // Send EOF so agy starts immediately
            }

            // Stream chunks directly from process stdout
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            val charBuffer = CharArray(512)

            while (true) {
                val charsRead = withContext(Dispatchers.IO) { reader.read(charBuffer) }
                if (charsRead <= 0) break

                val rawChunk = String(charBuffer, 0, charsRead)
                val cleanChunk = ANSI_REGEX.replace(rawChunk, "")
                if (cleanChunk.isNotEmpty()) {
                    emit(cleanChunk)
                }
            }

            withContext(Dispatchers.IO) { process.waitFor() }
        } catch (e: Exception) {
            Log.e(TAG, "Direct agy execution failed", e)
            emit("⚠️ Direct Anti-Gravity Execution Error: ${e.message}\n")
        } finally {
            try {
                process?.destroyForcibly()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}
