package com.aro.code.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Manages the In-App Embedded Mini Linux Subsystem (Alpine ARM64 via PRoot).
 * - Downloads lightweight rootfs (~3.9 MB)
 * - Extracts rootfs into app's private filesDir
 * - Configures DNS and mounts (/storage, /dev, /proc)
 * - Spawns fake-root Linux container using bundled libproot.so
 */
object LinuxSubsystem {

    private const val TAG = "LinuxSubsystem"
    private const val ROOTFS_DIR = "rootfs"
    private const val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, ROOTFS_DIR)
        val sh = File(rootfs, "bin/sh")
        return rootfs.exists() && sh.exists()
    }

    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, ROOTFS_DIR)
    }

    fun getProotBinary(context: Context): File? {
        // 1. Check native library directory (extracted by Android package manager)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "libproot.so")
        if (candidate.exists()) return candidate

        // 2. Check internal files directory fallback
        val internalCandidate = File(context.filesDir, "bin/proot")
        if (internalCandidate.exists()) return internalCandidate

        return null
    }

    suspend fun install(
        context: Context,
        onLog: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onLog("🐧 Starting In-App Mini Linux Setup (Alpine ARM64)...")
            val targetDir = File(context.filesDir, ROOTFS_DIR)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // 1. Download
            onLog("📥 Downloading Mini Linux rootfs (3.9 MB)...")
            val archiveFile = File(context.cacheDir, "alpine-minirootfs.tar.gz")
            if (archiveFile.exists()) archiveFile.delete()

            val url = URL(ROOTFS_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
            }

            if (conn.responseCode != 200) {
                onLog("❌ Download failed: HTTP ${conn.responseCode}")
                return@withContext false
            }

            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent && percent % 15 == 0) {
                                lastPercent = percent
                                onProgress(percent)
                                onLog("⏳ Downloading: $percent% ($downloaded bytes)")
                            }
                        }
                    }
                }
            }

            onLog("📦 Download complete (${archiveFile.length() / 1024} KB). Extracting rootfs...")

            // 2. Extract TAR.GZ directly
            extractTarGz(archiveFile, targetDir)
            onLog("✅ Rootfs extracted successfully!")

            // 3. Configure DNS (resolv.conf)
            val etcDir = File(targetDir, "etc").apply { mkdirs() }
            val resolvConf = File(etcDir, "resolv.conf")
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            onLog("🌐 DNS configured (Google & Cloudflare).")

            // 4. Create mountpoints
            File(targetDir, "storage").mkdirs()
            File(targetDir, "root/Download").mkdirs()

            // 5. Cleanup archive
            archiveFile.delete()

            onLog("─".repeat(44))
            onLog("🎉 In-App Mini Linux is READY!")
            onLog("💡 Commands available:")
            onLog("   apk add python3 py3-pip gcompat")
            onLog("   pip install fastapi uvicorn")
            onLog("   python /storage/Download/server.py")
            onLog("─".repeat(44))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            onLog("❌ Mini Linux installation error: ${e.message}")
            false
        }
    }

    /** Pure Java/Kotlin TAR.GZ streaming extractor */
    private fun extractTarGz(tarGzFile: File, destDir: File) {
        GZIPInputStream(FileInputStream(tarGzFile)).use { gzipIn ->
            val headerBuf = ByteArray(512)
            while (true) {
                var bytesRead = 0
                while (bytesRead < 512) {
                    val r = gzipIn.read(headerBuf, bytesRead, 512 - bytesRead)
                    if (r == -1) break
                    bytesRead += r
                }
                if (bytesRead < 512) break
                if (headerBuf.all { it == 0.toByte() }) break // EOF blocks

                // Name: 0..99
                val name = String(headerBuf, 0, 100).trim('\u0000').trim()
                if (name.isEmpty()) continue

                // Size: 124..135 (octal)
                val sizeStr = String(headerBuf, 124, 12).trim('\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L

                // Type: 156 ('0'/NUL = file, '5' = dir)
                val type = headerBuf[156].toInt().toChar()

                val outFile = File(destDir, name)

                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs()
                } else if (type == '0' || type == '\u0000') {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var remaining = size
                        val fileBuf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, fileBuf.size.toLong()).toInt()
                            val r = gzipIn.read(fileBuf, 0, toRead)
                            if (r == -1) break
                            fos.write(fileBuf, 0, r)
                            remaining -= r
                        }
                    }
                    outFile.setReadable(true, false)
                    outFile.setExecutable(true, false)

                    // Skip padding bytes to 512-byte boundary
                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) {
                        var skipped = 0L
                        while (skipped < padding) {
                            val s = gzipIn.skip(padding - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }
                } else {
                    // Skip content for symlinks or other entries
                    if (size > 0) {
                        val toSkip = size + ((512 - (size % 512)) % 512)
                        var skipped = 0L
                        while (skipped < toSkip) {
                            val s = gzipIn.skip(toSkip - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }
                }
            }
        }
    }

    /** Spawns the Linux container process */
    fun spawnProotProcess(context: Context): Process {
        val proot = getProotBinary(context)
            ?: throw IllegalStateException("libproot.so not found in nativeLibraryDir!")

        val rootfsPath = getRootfsDir(context).absolutePath

        val cmd = listOf(
            proot.absolutePath,
            "-r", rootfsPath,
            "-0", // Fake root ID
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/storage/emulated/0:/storage",
            "-b", "/storage/emulated/0/Download:/root/Download",
            "-w", "/root",
            "/bin/sh"
        )

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment()["TERM"] = "xterm-256color"
            environment()["HOME"] = "/root"
            environment()["PATH"] = "/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin"
            environment()["LANG"] = "C.UTF-8"
        }
        return pb.start()
    }
}
