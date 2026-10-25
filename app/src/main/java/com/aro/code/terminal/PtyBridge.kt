package com.aro.code.terminal

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PtyBridge — Kotlin JNI wrapper around libpty_bridge.so
 *
 * The native library uses POSIX openpty + fork + execve to create a real PTY.
 * TIOCSWINSZ is sent on every resize so programs receive SIGWINCH.
 *
 * I/O is done in Kotlin via FileDescriptor reflection (a standard Android
 * technique used by Termux, JuiceSSH, and ConnectBot). The master FD is
 * wrapped in a FileInputStream (reads) and FileOutputStream (writes) that
 * Kotlin coroutines consume from dedicated threads.
 */
object PtyBridge {

    private const val TAG = "PtyBridge"
    private var loaded = false

    /** Call once before any other method. Returns true if the library loaded. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("pty_bridge")
            loaded = true
            Log.i(TAG, "libpty_bridge.so loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libpty_bridge.so — PTY unavailable", e)
            false
        }
    }

    // ── Native declarations ────────────────────────────────────────────────

    /**
     * Fork a child process with a real PTY.
     * @param cmd   Command + arguments (cmd[0] must be an absolute path to the executable).
     * @param envp  Environment variables as "KEY=VALUE" strings.
     * @param cwd   Working directory for the child.
     * @param rows  Initial terminal height in rows.
     * @param cols  Initial terminal width in columns.
     * @return [masterFd, childPid] or null on error.
     */
    @JvmStatic
    external fun forkAndExec(
        cmd: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int
    ): IntArray?

    /**
     * Resize the PTY window → sends SIGWINCH to the process group.
     */
    @JvmStatic
    external fun resizePty(masterFd: Int, rows: Int, cols: Int): Int

    /**
     * Send a signal to the entire process group of [pid].
     * Use signal 9 (SIGKILL) to force-kill, 15 (SIGTERM) for graceful.
     */
    @JvmStatic
    external fun killProcessGroup(pid: Int, signal: Int): Int

    /**
     * Non-blocking waitpid(WNOHANG).
     * @return exit code ≥ 0, or -1 if still running, or -2 on error.
     */
    @JvmStatic
    external fun waitForExit(pid: Int): Int

    // ── FileDescriptor helpers ─────────────────────────────────────────────

    /**
     * Wrap a raw native FD integer into a Java [FileDescriptor].
     * This is the standard technique used by Termux, ConnectBot, JuiceSSH.
     */
    fun wrapFileDescriptor(fd: Int): FileDescriptor {
        val jfd = FileDescriptor()
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(jfd, fd)
        } catch (e: Exception) {
            Log.e(TAG, "wrapFileDescriptor reflection failed — trying descriptor field", e)
            try {
                // Android 12+ renamed field to "descriptor" but some builds differ
                val field = FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.setInt(jfd, fd)
            } catch (e2: Exception) {
                Log.e(TAG, "Both reflection paths failed for fd=$fd", e2)
            }
        }
        return jfd
    }

    /** Open the master FD for reading PTY output. */
    fun masterInputStream(masterFd: Int): FileInputStream =
        FileInputStream(wrapFileDescriptor(masterFd))

    /** Open the master FD for writing PTY input. */
    fun masterOutputStream(masterFd: Int): FileOutputStream =
        FileOutputStream(wrapFileDescriptor(masterFd))
}
