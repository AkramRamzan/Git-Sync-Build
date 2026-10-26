package com.aro.code.ai

import android.content.Context
import android.util.Log
import com.aro.code.terminal.LinuxSubsystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AntiGravityDirectEngine — High-performance persistent resident daemon engine for `agy`.
 *
 * Architecture:
 * - Keeps a persistent, warm `agy` process running in PRoot using `--input-format stream-json --output-format stream-json`.
 * - Zero cold-start latency: Node.js, PRoot, V8 VM, and auth credentials stay loaded in RAM.
 * - Streams responses instantly line-by-line (text_delta) with <50ms latency, matching CLI speed.
 * - Auto-reconnects seamlessly if the model changes or the daemon restarts.
 */
@Singleton
class AntiGravityDirectEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AntiGravityDirect"
        // Strip ANSI terminal color escapes for clean Jetpack Compose rendering
        private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")
    }

    private val sessionMutex = Mutex()
    private var activeProcess: Process? = null
    private var processWriter: BufferedWriter? = null
    private var processReader: BufferedReader? = null
    private var activeModel: String? = null
    private var activeProjectPath: String? = null

    /**
     * Checks if the embedded PRoot Linux subsystem and agy CLI binary are available.
     */
    fun isReady(): Boolean {
        if (!LinuxSubsystem.isInstalled(context)) return false
        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        return File(rootfsDir, "root/.local/bin/agy").exists() ||
               File(rootfsDir, "usr/local/bin/agy").exists() ||
               File(rootfsDir, "usr/bin/agy").exists()
    }

    /**
     * Warm up the daemon in the background so the very first prompt is instant.
     */
    suspend fun warmUp(modelId: String = "gemini-3.8-flash", projectPath: String? = null) {
        if (!isReady()) return
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    getOrCreateProcess(mapModelName(modelId), mapProjectPath(projectPath))
                } catch (e: Exception) {
                    Log.w(TAG, "warmUp failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Streams an Anti-Gravity AI prompt using the persistent resident daemon.
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

        val agyModelName = mapModelName(modelId)
        val guestCwd = mapProjectPath(projectPath)

        sessionMutex.withLock {
            val (writer, reader) = try {
                getOrCreateProcess(agyModelName, guestCwd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get or create agy daemon session", e)
                emit("⚠️ Failed to initialize in-app Anti-Gravity daemon: ${e.message}\n")
                return@flow
            }

            // Construct full prompt
            val fullPrompt = if (systemPrompt.isNotBlank()) {
                "$systemPrompt\n\n$prompt"
            } else {
                prompt
            }

            // Encode prompt into stream-json user event
            val userPayload = JSONObject().apply {
                put("event", "user")
                put("message", JSONObject().apply {
                    put("content", fullPrompt)
                })
            }.toString()

            // Send to warm resident daemon
            try {
                withContext(Dispatchers.IO) {
                    writer.write(userPayload)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write prompt to agy daemon, restarting session...", e)
                resetSession()
                emit("⚠️ Session connection interrupted. Retrying on next message...\n")
                return@flow
            }

            var turnCompleted = false
            var tokenCount = 0

            // Stream line-by-line responses from daemon's stdout
            while (!turnCompleted) {
                val line = try {
                    withContext(Dispatchers.IO) { reader.readLine() }
                } catch (e: Exception) {
                    null
                }

                if (line == null) {
                    Log.w(TAG, "agy daemon stdout closed unexpectedly")
                    resetSession()
                    if (tokenCount == 0) {
                        emit("⚠️ Anti-Gravity daemon closed unexpectedly. It will restart automatically.\n")
                    }
                    break
                }

                try {
                    val json = JSONObject(line)
                    when (json.optString("event")) {
                        "step_update" -> {
                            val stepUpdate = json.optJSONObject("step_update")
                            val delta = stepUpdate?.optString("text_delta")
                            if (!delta.isNullOrEmpty()) {
                                tokenCount++
                                val cleanDelta = ANSI_REGEX.replace(delta, "")
                                if (cleanDelta.isNotEmpty()) {
                                    emit(cleanDelta)
                                }
                            }
                        }
                        "result" -> {
                            turnCompleted = true
                            val resultObj = json.optJSONObject("result")
                            val status = resultObj?.optString("status")
                            if (status == "ERROR") {
                                val err = resultObj.optString("error")
                                if (err.isNotEmpty()) {
                                    emit("\n\n⚠️ Anti-Gravity Error: $err\n")
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip non-JSON debug lines
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Retrieves existing warm process or launches a new persistent daemon process.
     */
    private suspend fun getOrCreateProcess(
        modelName: String,
        guestCwd: String
    ): Pair<BufferedWriter, BufferedReader> {
        val proc = activeProcess
        val w = processWriter
        val r = processReader

        // If process is running and configuration matches, reuse it immediately (WARM path)
        if (proc != null && proc.isAlive && w != null && r != null &&
            activeModel == modelName && activeProjectPath == guestCwd
        ) {
            return Pair(w, r)
        }

        // Otherwise, reset and start fresh resident daemon
        resetSession()

        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        val proot = LinuxSubsystem.getProotBinary(context)
            ?: throw IllegalStateException("PRoot binary (libproot.so) not found")

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

        val addDirArg = if (guestCwd != "/root" && File(rootfsDir, guestCwd.trimStart('/')).exists()) {
            "--add-dir '$guestCwd'"
        } else {
            ""
        }

        // Launch in stream-json mode for persistent resident multi-turn execution
        val shellCmd = "exec /usr/local/bin/agy --model $modelName --dangerously-skip-permissions $addDirArg --input-format stream-json --output-format stream-json"

        cmd.addAll(listOf(
            "-w", guestCwd,
            "/bin/bash", "-c", shellCmd
        ))

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)

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

        val newProc = withContext(Dispatchers.IO) { pb.start() }
        val newWriter = newProc.outputStream.bufferedWriter(Charsets.UTF_8)
        val newReader = newProc.inputStream.bufferedReader(Charsets.UTF_8)

        // Read initial init line from agy
        val initLine = withContext(Dispatchers.IO) { newReader.readLine() }
        Log.i(TAG, "agy daemon initialized: $initLine")

        activeProcess = newProc
        processWriter = newWriter
        processReader = newReader
        activeModel = modelName
        activeProjectPath = guestCwd

        return Pair(newWriter, newReader)
    }

    /**
     * Resets active daemon session.
     */
    fun resetSession() {
        try {
            processWriter?.close()
        } catch (_: Exception) {}
        try {
            processReader?.close()
        } catch (_: Exception) {}
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}

        activeProcess = null
        processWriter = null
        processReader = null
        activeModel = null
        activeProjectPath = null
    }

    private fun mapModelName(modelId: String): String = when (modelId) {
        "gemini-3.8-flash"  -> "gemini-3.8-flash-high"
        "claude-sonnet-4.6" -> "claude-sonnet-4-6"
        "claude-opus-4.6"   -> "claude-opus-4-6-thinking"
        "gpt-oss-120b"      -> "gpt-oss-120b-medium"
        "gemini-4-argon"    -> "gemini-3.8-flash-high"
        else                -> modelId
    }

    private fun mapProjectPath(projectPath: String?): String = if (!projectPath.isNullOrBlank()) {
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
}
