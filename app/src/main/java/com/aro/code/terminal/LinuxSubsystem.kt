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

    /**
     * Copies the bundled server.py from APK assets → /storage/emulated/0/Download/server.py
     * Always overwrites so user always gets the latest version.
     */
    fun deployServerPy(context: Context): Boolean {
        return try {
            val dest = java.io.File("/storage/emulated/0/Download/server.py")
            context.assets.open("server.py").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "deployServerPy failed", e)
            false
        }
    }

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, ROOTFS_DIR)
        if (!rootfs.exists()) return false
        val sh = File(rootfs, "bin/sh")
        // File.exists() returns false for broken symlinks; use NOFOLLOW_LINKS to detect them too
        return sh.exists() || java.nio.file.Files.exists(
            sh.toPath(),
            java.nio.file.LinkOption.NOFOLLOW_LINKS
        )
    }

    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, ROOTFS_DIR)
    }

    fun getProotLoader(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "libproot_loader.so")
        if (candidate.exists() && candidate.canExecute()) return candidate

        val binDir = File(context.filesDir, "bin")
        val internalCandidate = File(binDir, "libproot_loader.so")
        if (internalCandidate.exists() && internalCandidate.canExecute()) return internalCandidate

        if (candidate.exists()) return candidate
        return null
    }

    fun getProotBinary(context: Context): File? {
        // 1. Check native library directory (extracted by Android package manager)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "libproot.so")
        if (candidate.exists() && candidate.canExecute()) return candidate

        // 2. Check internal files directory fallback
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val internalCandidate = File(binDir, "libproot.so")
        if (internalCandidate.exists() && internalCandidate.canExecute()) {
            return internalCandidate
        }

        // 3. Robust fallback: Extract libproot.so + dependencies directly from the installed APK file
        try {
            val apkPath = context.applicationInfo.sourceDir
            if (apkPath != null && File(apkPath).exists()) {
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val libsToExtract = listOf(
                        "libproot.so",
                        "libproot_loader.so",
                        "libproot_loader32.so",
                        "libtalloc.so",
                        "libandroid-shmem.so"
                    )
                    for (libName in libsToExtract) {
                        val candidateEntries = listOf(
                            "lib/arm64-v8a/$libName",
                            "lib/armeabi-v7a/$libName",
                            "lib/x86_64/$libName"
                        )
                        val entry = candidateEntries.firstNotNullOfOrNull { zip.getEntry(it) }
                        if (entry != null) {
                            val targetFile = File(binDir, libName)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            targetFile.setExecutable(true, false)
                            targetFile.setReadable(true, false)
                        }
                    }
                    if (internalCandidate.exists()) return internalCandidate
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract libproot.so from APK", e)
        }

        if (candidate.exists()) return candidate
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

            // 4. Create mountpoints and temp directory
            File(targetDir, "storage").mkdirs()
            File(targetDir, "root/Download").mkdirs()
            File(targetDir, "tmp").mkdirs()

            // 5. Cleanup archive
            archiveFile.delete()

            onLog("─".repeat(44))
            onLog("🎉 In-App Mini Linux is READY!")
            onLog("⏳ Installing Python3 & pip (direct download ~16MB)...")

            // Auto-bootstrap: bypass apk (causes Bad system call on Android seccomp)
            // Instead, directly extract Alpine .apk files (they are plain tar.gz archives)
            val bootstrapSuccess = installPythonPackages(context, targetDir, onLog)
            if (bootstrapSuccess) {
                onLog("✅ Python3 & pip installed successfully!")
            } else {
                onLog("⚠️ Python install incomplete — check internet & retry")
            }

            // ── Auto-deploy bundled server.py to /storage/emulated/0/Download/ ──
            try {
                val destServerPy = java.io.File("/storage/emulated/0/Download/server.py")
                context.assets.open("server.py").use { input ->
                    destServerPy.outputStream().use { output -> input.copyTo(output) }
                }
                onLog("✅ server.py deployed → /storage/Download/server.py")
            } catch (e: Exception) {
                onLog("⚠️ server.py copy failed: ${e.message}")
            }

            onLog("─".repeat(44))
            onLog("💡 Commands available:")
            onLog("   python3 --version")
            onLog("   pip install fastapi uvicorn")
            onLog("   python3 /storage/Download/server.py")
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

                // Name: 0..99 (null terminated)
                val rawName = String(headerBuf, 0, 100)
                val nameNull = rawName.indexOf('\u0000')
                val rawCleanName = if (nameNull != -1) rawName.substring(0, nameNull).trim() else rawName.trim()
                val name = rawCleanName.removePrefix("./").removePrefix("/")
                if (name.isEmpty()) continue

                // Size: 124..135 (octal)
                val sizeStr = String(headerBuf, 124, 12).trim('\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L

                // Type: 156 ('0'/NUL = file, '2' = symlink, '5' = dir)
                val type = headerBuf[156].toInt().toChar()

                val outFile = File(destDir, name)

                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs()
                } else if (type == '2') {
                    // ── Symlink entry ─────────────────────────────────────────
                    // linkname lives at bytes 157-256 in the TAR header (null terminated)
                    val rawTarget = String(headerBuf, 157, 100)
                    val targetNull = rawTarget.indexOf('\u0000')
                    val linkTarget = if (targetNull != -1) rawTarget.substring(0, targetNull).trim() else rawTarget.trim()

                    outFile.parentFile?.mkdirs()
                    // Safely delete any existing file or broken symlink at destination
                    runCatching { java.nio.file.Files.deleteIfExists(outFile.toPath()) }

                    // Convert absolute symlink to a relative path inside the rootfs
                    val resolvedTarget = if (linkTarget.startsWith("/")) {
                        // e.g. /bin/busybox -> busybox or ../../bin/busybox
                        val absTarget = File(destDir, linkTarget.trimStart('/'))
                        try {
                            outFile.parentFile?.toPath()?.relativize(absTarget.toPath())?.toString() ?: linkTarget
                        } catch (_: Exception) {
                            linkTarget
                        }
                    } else {
                        linkTarget
                    }
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(resolvedTarget)
                        )
                    }
                    // Symlinks have no data blocks — nothing to skip
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
                    // Skip content for other/unknown entry types
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

        val rootfsDir = getRootfsDir(context)
        val rootfsPath = rootfsDir.absolutePath

        // Ensure container directories exist
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "root/.local/bin").mkdirs()

        // Deploy smart agy wrapper so typing 'agy' never hangs without a PTY
        setupAgyWrapper(rootfsDir)

        // Host temporary directory for PRoot internal sockets & memory files
        val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

        // Prefer bash if installed, fallback to sh
        val shell = if (File(rootfsDir, "bin/bash").exists()) "/bin/bash" else "/bin/sh"

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

        val cmd = mutableListOf(
            proot.absolutePath,
            "-r", rootfsPath,
            "-0", // Fake root ID
            "--link2symlink",
            "--kill-on-exit"
        )
        cmd.addAll(bindings)
        cmd.addAll(listOf("-w", "/root", shell))

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val binDir = File(context.filesDir, "bin")
            environment()["LD_LIBRARY_PATH"] = "${nativeDir.absolutePath}:${binDir.absolutePath}"

            val loader = getProotLoader(context)
            if (loader != null && loader.exists()) {
                environment()["PROOT_LOADER"] = loader.absolutePath
            }
            val loader32Candidate = File(loader?.parentFile ?: nativeDir, "libproot_loader32.so")
            if (loader32Candidate.exists()) {
                environment()["PROOT_LOADER_32"] = loader32Candidate.absolutePath
            }

            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            environment()["PROOT_NO_SECCOMP"] = "1"
            environment()["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
            environment()["TMPDIR"] = prootTmp.absolutePath
            environment()["TEMP"] = prootTmp.absolutePath
            environment()["TMP"] = prootTmp.absolutePath
            environment()["TERM"] = "xterm-256color"
            environment()["HOME"] = "/root"
            environment()["PATH"] = "/root/.local/bin:/usr/local/bin:/usr/bin:/bin:/sbin:/usr/sbin"
            environment()["LANG"] = "C.UTF-8"
            environment()["PYTHONUNBUFFERED"] = "1"
            environment()["PYTHONIOENCODING"] = "utf-8"
        }
        return pb.start()
    }

    /**
     * Creates a smart wrapper at /usr/bin/agy inside rootfs.
     * When agy is invoked without arguments, standard pipes hang without a PTY.
     * This wrapper prompts for a task and executes agy -p "..." smoothly.
     */
    private fun setupAgyWrapper(rootfsDir: File) {
        try {
            val binDir = File(rootfsDir, "usr/bin").apply { mkdirs() }
            val agyWrapper = File(binDir, "agy")
            val script = """
                #!/bin/sh
                AGY_ORIG="/root/.local/bin/agy"
                if [ ! -f "${'$'}AGY_ORIG" ]; then
                    echo "❌ Antigravity CLI not installed at /root/.local/bin/agy"
                    echo "💡 Run to install: curl -fsSL https://antigravity.google/cli/install.sh | bash"
                    exit 1
                fi
                if [ ${'$'}# -eq 0 ]; then
                    echo "=========================================="
                    echo "🤖 Google Antigravity CLI (v1.4.7)"
                    echo "=========================================="
                    printf "Task / Prompt darj karein: "
                    read -r user_prompt
                    if [ -n "${'$'}user_prompt" ]; then
                        echo "⚡ Running: agy -p \"${'$'}user_prompt\"..."
                        exec "${'$'}AGY_ORIG" -p "${'$'}user_prompt" --dangerously-skip-permissions
                    else
                        echo "Cancelled."
                    fi
                else
                    exec "${'$'}AGY_ORIG" "${'$'}@"
                fi
            """.trimIndent()
            agyWrapper.writeText(script)
            agyWrapper.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "setupAgyWrapper failed: ${e.message}")
        }
    }

    /**
     * Installs Python3 & pip by directly downloading and extracting Alpine .apk files.
     * This completely bypasses `apk add` which triggers Bad system call (SIGSYS) on
     * Android's seccomp-restricted environment.
     *
     * Alpine .apk files are plain gzip-compressed tar archives — we extract them
     * exactly like the rootfs, skipping only package metadata files.
     */
    private suspend fun installPythonPackages(
        context: Context,
        destDir: File,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mainBase = "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/"
        val commBase = "https://dl-cdn.alpinelinux.org/alpine/v3.20/community/aarch64/"

        // Full dependency chain for python3 + pip + gcompat on Alpine ARM64 v3.20
        val packages = listOf(
            // gcompat (glibc compatibility — needed by pip/fastapi native modules)
            mainBase  to "musl-obstack-1.2.3-r2.apk",
            mainBase  to "libucontext-1.2-r3.apk",
            mainBase  to "gcompat-1.1.0-r4.apk",
            mainBase  to "libgcc-13.2.1_git20240309-r1.apk",
            mainBase  to "libstdc++-13.2.1_git20240309-r1.apk",
            // python3 dependencies
            mainBase  to "libbz2-1.0.8-r6.apk",
            mainBase  to "libexpat-2.8.1-r0.apk",
            mainBase  to "libffi-3.4.6-r0.apk",
            mainBase  to "gdbm-1.23-r1.apk",
            mainBase  to "xz-libs-5.8.3-r0.apk",
            mainBase  to "mpdecimal-4.0.0-r0.apk",
            mainBase  to "ncurses-terminfo-base-6.4_p20240420-r2.apk",
            mainBase  to "libncursesw-6.4_p20240420-r2.apk",
            mainBase  to "libpanelw-6.4_p20240420-r2.apk",
            mainBase  to "readline-8.2.10-r0.apk",
            mainBase  to "sqlite-libs-3.45.3-r3.apk",
            mainBase  to "libcrypto3-3.3.7-r0.apk",
            mainBase  to "libssl3-3.3.7-r0.apk",
            mainBase  to "zlib-1.3.2-r0.apk",
            mainBase  to "python3-3.12.13-r0.apk",
            // pip
            commBase  to "py3-pip-24.0-r2.apk",
            // FastAPI & Pydantic stack natively packaged by Alpine
            mainBase  to "py3-idna-3.7-r0.apk",
            commBase  to "py3-sniffio-1.3.1-r1.apk",
            commBase  to "py3-annotated-types-0.6.0-r1.apk",
            commBase  to "py3-anyio-4.3.0-r2.apk",
            commBase  to "py3-click-8.1.7-r2.apk",
            commBase  to "py3-h11-0.14.0-r4.apk",
            commBase  to "py3-typing-extensions-4.11.0-r1.apk",
            commBase  to "py3-pydantic-core-2.16.3-r1.apk",
            commBase  to "py3-pydantic-2.6.4-r1.apk",
            commBase  to "py3-starlette-0.37.2-r2.apk"
        )

        val tmpDir = File(context.cacheDir, "apk_pkgs").apply { mkdirs() }

        try {
            packages.forEachIndexed { index, (base, pkgName) ->
                val pkgUrl = "$base$pkgName"
                val pkgFile = File(tmpDir, pkgName)

                onLog("📦 [${index + 1}/${packages.size}] $pkgName")

                // Download package
                try {
                    val conn = (URL(pkgUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 60000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Alpine apk-tools/2.14")
                    }
                    if (conn.responseCode != 200) {
                        onLog("   ⚠️ HTTP ${conn.responseCode} — skipping $pkgName")
                        return@forEachIndexed
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(pkgFile).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    onLog("   ⚠️ Download failed: ${e.message} — skipping")
                    return@forEachIndexed
                }

                // Extract package (skip .SIGN.* and .PKGINFO metadata files)
                try {
                    extractAlpineApk(pkgFile, destDir)
                } catch (e: Exception) {
                    Log.e(TAG, "Extract failed for $pkgName", e)
                    onLog("   ⚠️ Extract error: ${e.message}")
                } finally {
                    pkgFile.delete()
                }
            }

            // Extract pure-python wheels for FastAPI + Uvicorn + SSE-Starlette directly into site-packages
            val sitePackagesDir = File(destDir, "usr/lib/python3.12/site-packages").apply { mkdirs() }
            val wheels = listOf(
                "https://files.pythonhosted.org/packages/e6/33/de41e554e5a187d583906e10d53bfae5fd6c07e98cbf4fe5262bd37e739a/fastapi-0.111.0-py3-none-any.whl",
                "https://files.pythonhosted.org/packages/b2/f9/e6f30ba6094733e4f9794fd098ca0543a19b07ac1fa3075d595bf0f1fb60/uvicorn-0.30.1-py3-none-any.whl",
                "https://files.pythonhosted.org/packages/61/98/658d1681bc6dc0b0f7f695aee4337de79decd0ccaec6d000a76b5cbdca6d/sse_starlette-3.5.1-py3-none-any.whl"
            )
            wheels.forEach { wheelUrl ->
                extractWheel(wheelUrl, sitePackagesDir, onLog)
            }

            // Create convenience symlinks only if they don't already exist
            val usrBin = File(destDir, "usr/bin")
            val pythonLink = File(usrBin, "python")
            if (!java.nio.file.Files.exists(pythonLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                runCatching {
                    java.nio.file.Files.createSymbolicLink(
                        pythonLink.toPath(),
                        java.nio.file.Paths.get("python3")
                    )
                }
            }
            val pipLink = File(usrBin, "pip")
            if (!java.nio.file.Files.exists(pipLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                runCatching {
                    java.nio.file.Files.createSymbolicLink(
                        pipLink.toPath(),
                        java.nio.file.Paths.get("pip3")
                    )
                }
            }

            // Verify python3 binary exists
            val python3 = File(destDir, "usr/bin/python3")
            val success = python3.exists() || java.nio.file.Files.exists(
                python3.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
            )
            if (success) {
                onLog("✅ python3 & pip ready at /usr/bin/")
            } else {
                onLog("⚠️ python3 binary not found after extraction")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "installPythonPackages failed", e)
            onLog("❌ Python install error: ${e.message}")
            false
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Extracts an Alpine .apk file (gzip-compressed tar) into destDir.
     * Skips package metadata: entries starting with ".SIGN." or named ".PKGINFO".
     * Uses the same TAR parsing logic as extractTarGz().
     */
    private fun extractAlpineApk(apkFile: File, destDir: File) {
        GZIPInputStream(FileInputStream(apkFile)).use { gzipIn ->
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
                val rawName = String(headerBuf, 0, 100)
                val nameNull = rawName.indexOf('\u0000')
                val rawCleanName = if (nameNull != -1) rawName.substring(0, nameNull).trim() else rawName.trim()
                val name = rawCleanName.removePrefix("./").removePrefix("/")

                // Size: 124..135 (octal)
                val sizeStr = String(headerBuf, 124, 12).trim('\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L

                // Type: 156
                val type = headerBuf[156].toInt().toChar()

                // Skip Alpine package metadata
                val baseName = name.substringAfterLast("/")
                val isMetadata = baseName.startsWith(".SIGN.") || baseName == ".PKGINFO" ||
                                 baseName == ".BUILDINFO" || baseName == ".COMMIT"
                if (isMetadata || name.isEmpty()) {
                    // Skip this entry's data blocks
                    if (size > 0) {
                        val toSkip = size + ((512 - (size % 512)) % 512)
                        var skipped = 0L
                        while (skipped < toSkip) {
                            val s = gzipIn.skip(toSkip - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }
                    continue
                }

                val outFile = File(destDir, name)

                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs()
                } else if (type == '2') {
                    // Symlink
                    val rawTarget = String(headerBuf, 157, 100)
                    val targetNull = rawTarget.indexOf('\u0000')
                    val linkTarget = if (targetNull != -1) rawTarget.substring(0, targetNull).trim() else rawTarget.trim()

                    outFile.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.deleteIfExists(outFile.toPath()) }

                    val resolvedTarget = if (linkTarget.startsWith("/")) {
                        val absTarget = File(destDir, linkTarget.trimStart('/'))
                        try {
                            outFile.parentFile?.toPath()?.relativize(absTarget.toPath())?.toString() ?: linkTarget
                        } catch (_: Exception) {
                            linkTarget
                        }
                    } else {
                        linkTarget
                    }
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(resolvedTarget)
                        )
                    }
                    // Symlinks have no data blocks
                } else if (type == '1') {
                    // Hardlink — treat as symlink to the link target
                    val rawTarget = String(headerBuf, 157, 100)
                    val targetNull = rawTarget.indexOf('\u0000')
                    val linkTarget = (if (targetNull != -1) rawTarget.substring(0, targetNull).trim() else rawTarget.trim())
                        .removePrefix("./").removePrefix("/")

                    outFile.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.deleteIfExists(outFile.toPath()) }

                    val absTarget = File(destDir, linkTarget)
                    val resolvedTarget = try {
                        outFile.parentFile?.toPath()?.relativize(absTarget.toPath())?.toString() ?: linkTarget
                    } catch (_: Exception) { linkTarget }

                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(resolvedTarget)
                        )
                    }
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
                    outFile.setWritable(true, false)
                    outFile.setExecutable(true, false)

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
                    // Unknown type — skip data
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

    /**
     * Downloads and extracts a pure-python wheel (.whl is a ZIP file) directly into site-packages.
     */
    private fun extractWheel(wheelUrl: String, sitePackagesDir: File, onLog: (String) -> Unit) {
        val fname = wheelUrl.substringAfterLast("/")
        onLog("📦 [wheel] $fname")
        try {
            val conn = (URL(wheelUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode != 200) {
                onLog("   ⚠️ HTTP ${conn.responseCode} — skipping $fname")
                return
            }
            sitePackagesDir.mkdirs()
            java.util.zip.ZipInputStream(conn.inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val outFile = File(sitePackagesDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var r: Int
                            while (zipIn.read(buffer).also { r = it } != -1) {
                                fos.write(buffer, 0, r)
                            }
                        }
                        outFile.setReadable(true, false)
                        outFile.setWritable(true, false)
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractWheel failed for $fname", e)
            onLog("   ⚠️ Wheel extract error: ${e.message}")
        }
    }
}
