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
 * - Keeps a persistent warm `agy` process alive in PRoot using stream-json protocol.
 * - Stores the conversation_id from the `init` event. If the process dies (Android OOM
 *   kill), the next launch passes `--conversation <id>` so the SAME cloud session is
 *   resumed — no "General Greeting" fragmentation and full context restored.
 * - After each result, immediately pre-warms the next process in background so the
 *   next prompt is never slow.
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

    // The current session's conversation_id from agy's `init` event.
    // Persisted across process restarts via --conversation flag so the
    // cloud UI always shows ONE session per user session.
    var activeConversationId: String? = null
        private set

    // ─── Public API ───────────────────────────────────────────────────────────

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
     * Pre-warms the daemon in the background. Safe to call multiple times.
     * Only spawns a new process if one isn't already running.
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
     * Ends the current conversation and starts a fresh one.
     * Immediately pre-warms a new process in background so the first prompt
     * of the new session is fast.
     */
    fun resetSession(
        modelId: String = "gemini-3.8-flash",
        projectPath: String? = null
    ) {
        killProcess()
        activeConversationId = null   // clear → next launch gets a brand new UUID
        // Pre-launch new warm process immediately in background
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
     *
     * If the process has died (Android OOM kill), relaunches it with
     * `--conversation <activeConversationId>` so the cloud session and context
     * are restored automatically. The user sees one continuous session.
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
                Log.e(TAG, "Failed to get/create agy daemon", e)
                emit("⚠️ Failed to initialize in-app Anti-Gravity daemon: ${e.message}\n")
                return@flow
            }

            val fullPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$prompt" else prompt

            val userPayload = JSONObject().apply {
                put("event", "user")
                put("message", JSONObject().apply { put("content", fullPrompt) })
            }.toString()

            try {
                withContext(Dispatchers.IO) {
                    writer.write(userPayload)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write failed, will retry on next prompt", e)
                killProcess()
                emit("⚠️ Session connection interrupted. Please try again.\n")
                return@flow
            }

            var turnCompleted = false
            var tokenCount = 0

            while (!turnCompleted) {
                val line = try {
                    withContext(Dispatchers.IO) { reader.readLine() }
                } catch (e: Exception) { null }

                if (line == null) {
                    Log.w(TAG, "agy stdout closed unexpectedly")
                    killProcess()
                    if (tokenCount == 0) {
                        emit("⚠️ Anti-Gravity daemon closed. Please try again.\n")
                    }
                    // Background pre-warm so next prompt is instant
                    scheduleRewarm(agyModelName, guestCwd)
                    break
                }

                try {
                    val json = JSONObject(line)
                    when (json.optString("event")) {
                        "init" -> {
                            val cid = json.optString("conversation_id")
                            if (cid.isNotEmpty()) {
                                activeConversationId = cid
                                Log.i(TAG, "agy init: conversation_id=$cid model=$agyModelName")
                            }
                        }
                        "step_update" -> {
                            val su = json.optJSONObject("step_update") ?: return@try
                            if (su.optString("step_type") == "agent_response") {
                                val delta = su.optString("text_delta")
                                if (!delta.isNullOrEmpty()) {
                                    val clean = ANSI_REGEX.replace(delta, "")
                                    if (clean.isNotEmpty() && clean != "\n") {
                                        tokenCount++
                                        emit(clean)
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
                            // Process may still be alive waiting for next input.
                            // Schedule a background liveness check: if it died, pre-warm
                            // immediately so the next prompt never cold-starts.
                            val capturedModel = agyModelName
                            val capturedCwd = guestCwd
                            schedulePostResultRewarm(capturedModel, capturedCwd)
                        }
                    }
                } catch (_: Exception) { /* skip non-JSON debug lines */ }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Checks if current process is alive. If not, starts a fresh one.
     * CRITICAL: if we have a stored conversation_id, passes --conversation so
     * the cloud session is resumed (same UUID → one session in cloud UI).
     */
    private suspend fun ensureProcessAlive(
        modelName: String,
        guestCwd: String
    ): Pair<BufferedWriter, BufferedReader> {
        val proc = activeProcess
        val w = processWriter
        val r = processReader

        // Warm path: reuse existing alive process (0ms overhead)
        if (proc != null && isProcessAlive(proc) && w != null && r != null &&
            activeModel == modelName
        ) {
            return Pair(w, r)
        }

        // Cold path: kill stale references, start fresh
        killProcess()

        val rootfsDir = LinuxSubsystem.getRootfsDir(context)
        val proot = LinuxSubsystem.getProotBinary(context)
            ?: throw IllegalStateException("PRoot binary not found")

        val bindings = mutableListOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        val extStorage = File("/storage/emulated/0")
        if (extStorage.exists()) {
            bindings += listOf("-b", "/storage/emulated/0:/storage")
            if (File("/storage/emulated/0/Download").exists()) {
                bindings += listOf("-b", "/storage/emulated/0/Download:/root/Download")
            }
        }

        val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

        val cmd = mutableListOf(
            proot.absolutePath,
            "-v", "-1",
            "-r", rootfsDir.absolutePath,
            "-0", "--link2symlink", "-L", "--sysvipc",
            "-k", "6.17.0-PRoot-Distro"
            // No --kill-on-exit: let agy outlive the bash wrapper
        )
        cmd.addAll(bindings)

        val addDirArg = if (guestCwd != "/root" &&
            File(rootfsDir, guestCwd.trimStart('/')).exists()
        ) "--add-dir '$guestCwd'" else ""

        // If we have a stored conversation_id, resume it so same cloud session is reused
        val conversationArg = activeConversationId?.let { "--conversation $it" } ?: ""

        val shellCmd = buildString {
            append("exec /usr/local/bin/agy")
            append(" --model $modelName")
            append(" --dangerously-skip-permissions")
            append(" --disable-slash-commands")
            if (conversationArg.isNotEmpty()) append(" $conversationArg")
            if (addDirArg.isNotEmpty()) append(" $addDirArg")
            append(" --input-format stream-json")
            append(" --output-format stream-json")
        }

        cmd.addAll(listOf("-w", guestCwd, "/bin/bash", "-c", shellCmd))

        val pb = ProcessBuilder(cmd).apply { redirectErrorStream(false) }
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
            remove("ANDROID_DATA"); remove("ANDROID_ROOT"); remove("ANDROID_STORAGE")
            remove("ANDROID_ART_ROOT"); remove("ANDROID_I18N_ROOT")
            remove("ANDROID_TZDATA_ROOT"); remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH"); remove("PROOT_LOADER"); remove("PROOT_LOADER_32")
        }

        val newProc = withContext(Dispatchers.IO) { pb.start() }
        val newWriter = newProc.outputStream.bufferedWriter(Charsets.UTF_8)
        val newReader = newProc.inputStream.bufferedReader(Charsets.UTF_8)

        // Drain stderr continuously so the pipe buffer never fills and blocks agy
        CoroutineScope(Dispatchers.IO).launch {
            try {
                newProc.errorStream.bufferedReader(Charsets.UTF_8)
                    .useLines { it.forEach { l -> Log.d(TAG, "[agy-stderr] $l") } }
            } catch (_: Exception) {}
        }

        // Read init line — contains the conversation_id for this process
        val initLine = withContext(Dispatchers.IO) { newReader.readLine() }
        if (initLine != null) {
            try {
                val obj = JSONObject(initLine)
                val cid = obj.optString("conversation_id")
                if (cid.isNotEmpty()) {
                    // Only update if we didn't already have one (resume keeps old id)
                    if (activeConversationId == null) activeConversationId = cid
                    Log.i(TAG, "agy process started: conversation_id=$cid resume=${conversationArg.isNotEmpty()}")
                }
            } catch (_: Exception) {
                Log.d(TAG, "agy init line: $initLine")
            }
        }

        activeProcess = newProc
        processWriter = newWriter
        processReader = newReader
        activeModel = modelName

        return Pair(newWriter, newReader)
    }

    /**
     * After a result, wait briefly then check if agy is still alive.
     * If it died, pre-warm immediately so the next prompt hits a warm process.
     */
    private fun schedulePostResultRewarm(modelName: String, guestCwd: String) {
        engineScope.launch {
            delay(500) // let agy settle
            val proc = activeProcess
            if (proc == null || !isProcessAlive(proc)) {
                // Process died after result — pre-warm now (keeps same conversationId)
                try {
                    sessionMutex.withLock {
                        ensureProcessAlive(modelName, guestCwd)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "post-result rewarm failed: ${e.message}")
                }
            }
            // If still alive — great, it will serve the next prompt immediately
        }
    }

    private fun scheduleRewarm(modelName: String, guestCwd: String) {
        engineScope.launch {
            delay(200)
            try {
                sessionMutex.withLock { ensureProcessAlive(modelName, guestCwd) }
            } catch (e: Exception) {
                Log.w(TAG, "rewarm failed: ${e.message}")
            }
        }
    }

    private fun isProcessAlive(proc: Process): Boolean = try {
        proc.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
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

    private fun mapProjectPath(projectPath: String?): String =
        if (!projectPath.isNullOrBlank()) when {
            projectPath.startsWith("/storage/emulated/0") ->
                projectPath.replaceFirst("/storage/emulated/0", "/storage")
            projectPath.startsWith("/sdcard") ->
                projectPath.replaceFirst("/sdcard", "/storage")
            else -> "/root"
        } else "/root"
}
