package com.aro.code.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PtySession — one real PTY + child process, bridged to xterm.js.
 *
 * Lifecycle:
 *  start(cmd, envp, cwd, rows, cols) → starts child + reader loop
 *  write(bytes)                       → sends keystrokes / paste to PTY
 *  resize(rows, cols)                 → TIOCSWINSZ → SIGWINCH in child
 *  stop()                             → SIGKILL process group, close FD
 *
 * Thread model:
 *  - A single coroutine reads the master FD continuously and delivers
 *    byte arrays to [onOutput] (called on an IO thread, buffer ~16 ms).
 *  - Writes are sent directly from whichever coroutine calls write().
 *
 * [onOutput] and [onExit] are set by the owner (XtermJsInterface) before
 * calling start().
 */
class PtySession(val id: Int) {

    companion object {
        private const val TAG = "PtySession"
        private const val READ_BUFFER = 8192
        private const val FLUSH_INTERVAL_MS = 16L  // ~60 fps batching
    }

    // ── Callbacks ─────────────────────────────────────────────────────────
    var onOutput: ((ByteArray) -> Unit)? = null
    var onExit:   ((Int) -> Unit)? = null

    // ── State ──────────────────────────────────────────────────────────────
    var isRunning = false
        private set

    private var masterFd   = -1
    private var childPid   = -1
    private var inputStream:  FileInputStream?  = null
    private var outputStream: FileOutputStream? = null
    private val stopped = AtomicBoolean(false)
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Start ──────────────────────────────────────────────────────────────

    fun start(
        cmd:  Array<String>,
        envp: Array<String>,
        cwd:  String,
        rows: Int,
        cols: Int
    ) {
        if (isRunning) return
        if (!PtyBridge.ensureLoaded()) {
            Log.e(TAG, "libpty_bridge not available — PTY session cannot start")
            onExit?.invoke(127)
            return
        }

        val result = PtyBridge.forkAndExec(cmd, envp, cwd, rows, cols)
        if (result == null || result.size < 2) {
            Log.e(TAG, "forkAndExec returned null for cmd=${cmd.firstOrNull()}")
            onExit?.invoke(1)
            return
        }

        masterFd = result[0]
        childPid = result[1]
        Log.i(TAG, "Session $id started: masterFd=$masterFd  pid=$childPid")

        inputStream  = PtyBridge.masterInputStream(masterFd)
        outputStream = PtyBridge.masterOutputStream(masterFd)
        isRunning    = true
        stopped.set(false)

        // Reader loop: read chunks from PTY master, batch for ~16 ms, deliver
        scope.launch(Dispatchers.IO) {
            val buf   = ByteArray(READ_BUFFER)
            val batch = ArrayList<Byte>(READ_BUFFER)
            var lastFlush = System.currentTimeMillis()

            try {
                while (isActive && !stopped.get()) {
                    val n = try { inputStream!!.read(buf) } catch (e: IOException) { -1 }
                    if (n == -1) break

                    for (i in 0 until n) batch.add(buf[i])

                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= FLUSH_INTERVAL_MS || batch.size >= READ_BUFFER) {
                        if (batch.isNotEmpty()) {
                            val chunk = batch.toByteArray()
                            batch.clear()
                            onOutput?.invoke(chunk)
                        }
                        lastFlush = now
                    }
                }
                // Flush remaining
                if (batch.isNotEmpty()) onOutput?.invoke(batch.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "Reader error session $id", e)
            }

            // Wait for exit code
            var exitCode = 0
            for (attempt in 0..20) {
                val code = PtyBridge.waitForExit(childPid)
                if (code >= 0) { exitCode = code; break }
                kotlinx.coroutines.delay(100)
            }
            Log.i(TAG, "Session $id exited with code $exitCode")
            isRunning = false
            onExit?.invoke(exitCode)
        }
    }

    // ── Write (keystrokes / paste → PTY) ──────────────────────────────────

    fun write(data: ByteArray) {
        if (!isRunning || outputStream == null) return
        try {
            outputStream!!.write(data)
            outputStream!!.flush()
        } catch (e: IOException) {
            Log.w(TAG, "write error session $id: ${e.message}")
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────────

    fun resize(rows: Int, cols: Int) {
        if (masterFd < 0) return
        if (!PtyBridge.ensureLoaded()) return
        val rc = PtyBridge.resizePty(masterFd, rows, cols)
        if (rc < 0) Log.w(TAG, "resizePty failed for session $id")
        else Log.d(TAG, "Session $id resized to ${rows}x${cols}")
    }

    // ── Stop ───────────────────────────────────────────────────────────────

    fun stop() {
        if (stopped.getAndSet(true)) return
        isRunning = false
        Log.i(TAG, "Stopping session $id (pid=$childPid)")

        if (childPid > 0 && PtyBridge.ensureLoaded()) {
            PtyBridge.killProcessGroup(childPid, 15) // SIGTERM
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(300)
                if (PtyBridge.waitForExit(childPid) < 0)
                    PtyBridge.killProcessGroup(childPid, 9) // SIGKILL
            }
            childPid = -1
        }

        try { inputStream?.close()  } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        inputStream  = null
        outputStream = null
        masterFd     = -1

        scope.cancel()
    }
}
