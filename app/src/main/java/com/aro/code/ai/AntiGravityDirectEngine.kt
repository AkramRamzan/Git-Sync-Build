package com.aro.code.ai

import android.content.Context
import android.util.Log
import com.aro.code.terminal.LinuxSubsystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
 * - Keeps a persistent, warm `agy` process running in PRoot using stream-json protocol.
 * - Background watchdog auto-restarts the process the moment it dies, so the next prompt
 *   always hits a warm process (zero cold-start delay).
 * - One conversation_id per session → no "General Greeting" spam in the cloud UI.
 * - All turns within one session share the same process → full context memory.
 */
@Singleton
class AntiGravityDirectEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AntiGravityDirect"
        private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionMutex = Mutex()

    private var activeProcess: Process? = null
    private var processWriter: BufferedWriter? = null
    private var processReader: BufferedReader? = null
    private var activeModel: String? = null

    // Exposed for optional UI display
    var activeConversationId: String? = null
        private set

    // --- Public API ---

    /**
     * Returns true if the embedded PRoot Linux subsystem and agy binary are available.
     */
    fun isReady(): Boolean {
        if (!LinuxSubsystem.isInstalled(context)) return false
        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        return File(rootfsDir, "root/.local/bin/agy").exists() ||
               File(rootfsDir, "usr/local/bin/agy").exists() ||
               File(rootfsDir, "usr/bin/agy").exists()
    }

    /**
     * Pre-warms the daemon in the background so the first prompt is instant.
     * Safe to call multiple times — only spawns if not already alive.
     */
    suspend fun warmUp(modelId: String = "gemini-3.8-flash", projectPath: String? = null) {
        if (!isReady()) return
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    ensureProcessAlive(mapModelName(modelId), mapProjectPath(projectPath))
                } catch (e: Exception) {
                    Log.w(TAG, "warmUp failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Kills the current warm daemon so the next prompt starts a fresh agy process
     * with a new conversation_id. Call this on "New Session" or "Clear History".
     * Immediately kicks off a background re-warm so the next prompt is still fast.
     */
    fun resetSession(
        modelId: String = "gemini-3.8-flash",
        projectPath: String? = null
    ) {
        killProcess()
        activeConversationId = null
        // Pre-launch a new warm process in background immediately
        if (isReady()) {
            engineScope.launch {
                try {
                    sessionMutex.withLock {
                        ensureProcessAlive(mapModelName(modelId), mapProjectPath(projectPath))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "resetSession re-warm failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Streams a prompt to the persistent warm agy daemon.
     * If the process has died, restarts it (one cold-start penalty, then warm forever).
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
                ensureProcessAlive(agyModelName, guestCwd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get or create agy daemon session", e)
                emit("⚠️ Failed to initialize in-app Anti-Gravity daemon: ${e.message}\n")
                return@flow
            }

            val fullPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$prompt" else prompt

            val userPayload = JSONObject().apply {
                put("event", "user")
                put("message", JSONObject().apply {
                    put("content", fullPrompt)
                })
            }.toString()

            try {
                withContext(Dispatchers.IO) {
                    writer.write(userPayload)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to agy daemon, restarting...", e)
                killProcess()
                emit("⚠️ Session connection interrupted. Please try again.\n")
                return@flow
            }

            var turnCompleted = false
            var tokenCount = 0

            while (!turnCompleted) {
                val line = try {
                    withContext(Dispatchers.IO) { reader.readLine() }
                } catch (e: Exception) {
                    null
                }

                if (line == null) {
                    Log.w(TAG, "agy daemon stdout closed unexpectedly")
                    killProcess()
                    if (tokenCount == 0) {
                        emit("⚠️ Anti-Gravity daemon closed unexpectedly. Please try again.\n")
                    }
                    // Immediately kick off background re-warm for next prompt
                    engineScope.launch {
                        delay(200)
                        try {
                            sessionMutex.withLock {
                                ensureProcessAlive(agyModelName, guestCwd)
                            }
                        } catch (_: Exception) {}
                    }
                    break
                }

                try {
                    val json = JSONObject(line)
                    when (json.optString("event")) {
                        "init" -> {
                            val cid = json.optString("conversation_id")
                            if (cid.isNotEmpty()) {
                                activeConversationId = cid
                                Log.i(TAG, "agy session conversation_id=$cid")
                            }
                        }
                        "step_update" -> {
                            val stepUpdate = json.optJSONObject("step_update")
                            val state = stepUpdate?.optString("state")
                            val stepType = stepUpdate?.optString("step_type")
                            // Emit text only from agent_response steps
                            if (stepType == "agent_response") {
                                val delta = stepUpdate?.optString("text_delta")
                                if (!delta.isNullOrEmpty()) {
                                    val cleanDelta = ANSI_REGEX.replace(delta, "")
                                    if (cleanDelta.isNotEmpty() && cleanDelta != "\n") {
                                        tokenCount++
                                        emit(cleanDelta)
                                    }
                                }
                            }
                        }
                        "result" -> {
                            turnCompleted = true
                            val resultObj = json.optJSONObject("result")
                            val status = resultObj?.optString("status")
                            if (status == "ERROR") {
                                val err = resultObj?.optString("error") ?: ""
                                if (err.isNotEmpty()) emit("\n\n⚠️ Anti-Gravity Error: $err\n")
                            }
                            // Process stays alive — do NOT kill it. Next prompt reuses same process.
                        }
                    }
                } catch (_: Exception) {
                    // Skip non-JSON lines (debug output etc.)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // --- Internal helpers ---

    /**
     * Returns the existing warm process if alive and model matches,
     * otherwise kills it and starts a new one. Parses the `init` event.
     */
    private suspend fun ensureProcessAlive(
        modelName: String,
        guestCwd: String
    ): Pair<BufferedWriter, BufferedReader> {
        val proc = activeProcess
        val w = processWriter
        val r = processReader

        // Reuse existing process if alive and same model (warm path — zero overhead)
        if (proc != null && isProcessAlive(proc) && w != null && r != null &&
            activeModel == modelName
        ) {
            return Pair(w, r)
        }

        // Otherwise, kill stale process and start a fresh daemon
        killProcess()

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
            "-k", "6.17.0-PRoot-Distro"
            // NOTE: No --kill-on-exit so child agy process survives between turns
        )
        cmd.addAll(bindings)

        val addDirArg = if (guestCwd != "/root" && File(rootfsDir, guestCwd.trimStart('/')).exists()) {
            "--add-dir '$guestCwd'"
        } else {
            ""
        }

        // Launch agy in persistent stream-json mode
        val shellCmd = buildString {
            append("exec /usr/local/bin/agy")
            append(" --model $modelName")
            append(" --dangerously-skip-permissions")
            append(" --disable-slash-commands")
            if (addDirArg.isNotEmpty()) append(" $addDirArg")
            append(" --input-format stream-json")
            append(" --output-format stream-json")
        }

        cmd.addAll(listOf("-w", guestCwd, "/bin/bash", "-c", shellCmd))

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

        // Drain stderr so pipe buffer never fills (blocks child process writes)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                newProc.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, "[agy-stderr] $line") }
                }
            } catch (_: Exception) {}
        }

        // Read the init event — contains conversation_id
        val initLine = withContext(Dispatchers.IO) { newReader.readLine() }
        if (initLine != null) {
            try {
                val initJson = JSONObject(initLine)
                val cid = initJson.optString("conversation_id")
                if (cid.isNotEmpty()) {
                    activeConversationId = cid
                    Log.i(TAG, "agy daemon started: conversation_id=$cid model=$modelName")
                }
            } catch (_: Exception) {
                Log.i(TAG, "agy daemon init: $initLine")
            }
        }

        activeProcess = newProc
        processWriter = newWriter
        processReader = newReader
        activeModel = modelName

        // Launch watchdog: when this process dies, immediately start a replacement
        engineScope.launch {
            try {
                withContext(Dispatchers.IO) { newProc.waitFor() }
            } catch (_: Exception) {}
            Log.w(TAG, "agy daemon exited — scheduling background re-warm")
            // Only restart if this is still our active process
            if (activeProcess === newProc) {
                killProcess()
                delay(300)
                try {
                    sessionMutex.withLock {
                        ensureProcessAlive(modelName, guestCwd)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "watchdog re-warm failed: ${e.message}")
                }
            }
        }

        return Pair(newWriter, newReader)
    }

    private fun isProcessAlive(proc: Process): Boolean = try {
        proc.exitValue()
        false // exitValue() returns without exception → process has exited
    } catch (_: IllegalThreadStateException) {
        true  // throws if still running
    }

    private fun killProcess() {
        try { processWriter?.close() } catch (_: Exception) {}
        try { processReader?.close() } catch (_: Exception) {}
        try { activeProcess?.destroyForcibly() } catch (_: Exception) {}
        activeProcess = null
        processWriter = null
        processReader = null
        activeModel = null
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
