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
 * Manages the In-App Embedded Ubuntu Linux Subsystem (Ubuntu 22.04 LTS ARM64 via PRoot).
 * - Downloads pre-packaged Ubuntu rootfs (~85 MB) with Git, Zip, Unzip, Curl, Python3, Pip
 * - Extracts rootfs with GNU LongLink / HardLink support into app's private filesDir
 * - Configures DNS, APT Sandbox, hosts, and mounts (/storage, /dev, /proc, /sys)
 * - Spawns fake-root Linux container using bundled libproot.so
 */
object LinuxSubsystem {

    private const val TAG = "LinuxSubsystem"
    private const val ROOTFS_DIR = "rootfs"

    // High-speed CDN releases asset with pre-installed Git, Zip, Unzip, Curl, Python3, Pip
    private const val UBUNTU_FULL_ROOTFS_URL =
        "https://github.com/AkramRamzan/Git-Sync-Build/releases/download/ubuntu-rootfs/ubuntu-22.04-arm64-full.tar.gz"

    // Canonical official base image fallback
    private const val UBUNTU_BASE_FALLBACK_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"

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
            Log.e(TAG, "deployServerPy failed", e)
            false
        }
    }

    fun isInstalled(context: Context): Boolean {
        val rootfs = File(context.filesDir, ROOTFS_DIR)
        if (!rootfs.exists()) return false
        val bash = File(rootfs, "bin/bash")
        val sh = File(rootfs, "bin/sh")
        return bash.exists() || sh.exists() || java.nio.file.Files.exists(
            bash.toPath(),
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

        // 3. Robust fallback: Extract libproot.so + dependencies directly from installed APK
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
            onLog("🐧 Starting In-App Ubuntu Linux Setup (22.04 LTS ARM64)...")
            val targetDir = File(context.filesDir, ROOTFS_DIR)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // 1. Download — first check local Download folder cache
            val downloadFolder = File("/storage/emulated/0/Download")
            val cachedRootfs = File(downloadFolder, "ubuntu-rootfs.tar.gz")
            val archiveFile = File(context.cacheDir, "ubuntu-rootfs.tar.gz")
            if (archiveFile.exists()) archiveFile.delete()

            if (cachedRootfs.exists() && cachedRootfs.length() > 10 * 1024 * 1024) {
                // Use cached copy from Download folder — no re-download needed
                onLog("📦 Found cached rootfs in Download folder (${cachedRootfs.length() / 1024 / 1024} MB) — skipping download!")
                onLog("📂 Using: /storage/emulated/0/Download/ubuntu-rootfs.tar.gz")
                onProgress(100)
                cachedRootfs.copyTo(archiveFile, overwrite = true)
            } else {
                onLog("📥 Downloading Ubuntu Rootfs (Git, Zip, Python3 pre-installed)...")
                val downloaded = downloadWithFallback(UBUNTU_FULL_ROOTFS_URL, UBUNTU_BASE_FALLBACK_URL, archiveFile, onLog, onProgress)
                if (!downloaded || !archiveFile.exists() || archiveFile.length() < 1000) {
                    onLog("❌ Download failed from all mirrors")
                    return@withContext false
                }
                // Save a copy to Download folder for future re-installs
                try {
                    if (downloadFolder.exists() && downloadFolder.canWrite()) {
                        onLog("💾 Saving copy to Download folder for future use...")
                        archiveFile.copyTo(cachedRootfs, overwrite = true)
                        onLog("✅ Cached → /storage/emulated/0/Download/ubuntu-rootfs.tar.gz")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cache rootfs to Download folder: ${e.message}")
                }
            }

            onLog("📦 Download complete (${archiveFile.length() / 1024 / 1024} MB). Extracting Ubuntu rootfs...")

            // 2. Extract TAR.GZ directly
            extractTarGz(archiveFile, targetDir)
            onLog("✅ Ubuntu rootfs extracted successfully!")

            // 3. Configure DNS (resolv.conf)
            val etcDir = File(targetDir, "etc").apply { mkdirs() }
            File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            onLog("🌐 DNS configured (Google & Cloudflare).")

            // 4. Configure APT Sandbox + Progress + Performance (required for Android PRoot)
            val aptConfDir = File(etcDir, "apt/apt.conf.d").apply { mkdirs() }
            // Sandbox fix for PRoot (no user separation)
            File(aptConfDir, "01sandbox").writeText("APT::Sandbox::User \"root\";\nDir::Log::Terminal \"\";\n")
            // Disable binary package cache — prevents "Reading package lists..." from freezing in PRoot
            File(aptConfDir, "02nocache").writeText(
                "Dir::Cache::pkgcache \"\";\nDir::Cache::srcpkgcache \"\";\n"
            )
            // Enable fancy dpkg progress bar (shows % like Ubuntu/Kali terminal)
            File(aptConfDir, "03progress").writeText(
                "Dpkg::Progress-Fancy \"1\";\nAPT::Color \"1\";\n"
            )

            // PERFORMANCE FIX: Replace full sources.list with minimal repos only
            // Full Ubuntu sources = 50MB package lists (universe+multiverse+backports) → slow in PRoot
            // Minimal sources = ~10MB (main+universe from jammy + jammy-security only) → 5x faster!
            File(etcDir, "apt/sources.list").writeText("""
deb http://ports.ubuntu.com/ubuntu-ports jammy main restricted universe
deb http://ports.ubuntu.com/ubuntu-ports jammy-updates main restricted universe
deb http://ports.ubuntu.com/ubuntu-ports jammy-security main restricted universe
""".trimIndent()
            )

            // 5. Configure Hosts & Environment
            File(etcDir, "hosts").writeText("127.0.0.1 localhost arocode\n::1 localhost ip6-localhost ip6-loopback\n")
            File(etcDir, "environment").writeText("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\nLANG=\"C.UTF-8\"\n")

            // Unset Android variables in profile & bashrc so python pip / packages run as standard Linux
            val unsetAndroidVars = "\nunset ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT\n"
            File(etcDir, "profile").appendText(unsetAndroidVars)
            val rootDir = File(targetDir, "root").apply { mkdirs() }
            File(rootDir, ".bashrc").appendText(unsetAndroidVars)
            File(rootDir, ".bashrc").appendText("\nexport PS1='root@arocode:~# '\n")

            // 6. Create mountpoints and temp directory
            File(targetDir, "storage").mkdirs()
            File(targetDir, "root/Download").mkdirs()
            val tmpDir = File(targetDir, "tmp").apply { mkdirs() }
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            tmpDir.setExecutable(true, false)

            // 7. Cleanup archive
            archiveFile.delete()

            // 8. Auto-deploy bundled server.py
            deployServerPy(context)
            onLog("✅ server.py deployed → /storage/Download/server.py")

            // 9. Setup agy & apt wrappers
            setupAgyWrapper(targetDir)
            setupAptWrapper(targetDir)

            onLog("─".repeat(44))
            onLog("🎉 In-App Ubuntu Linux (ARM64 GLIBC) is READY!")
            onLog("💡 Commands available:")
            onLog("   git --version")
            onLog("   zip -v")
            onLog("   python3 --version")
            onLog("   pip list")
            onLog("   agy (Google Antigravity CLI)")
            onLog("   python3 /storage/Download/server.py")
            onLog("─".repeat(44))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            onLog("❌ Ubuntu Linux installation error: ${e.message}")
            false
        }
    }

    private fun downloadWithFallback(
        primaryUrl: String,
        fallbackUrl: String,
        destFile: File,
        onLog: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (downloadFile(primaryUrl, destFile, onLog, onProgress)) {
            return true
        }
        onLog("⚠️ Primary mirror unavailable, trying Canonical base fallback...")
        return downloadFile(fallbackUrl, destFile, onLog, onProgress)
    }

    private fun downloadFile(
        urlStr: String,
        destFile: File,
        onLog: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val redirect = conn.getHeaderField("Location")
                if (redirect != null) {
                    return downloadFile(redirect, destFile, onLog, onProgress)
                }
            }
            if (code !in 200..299) {
                return false
            }

            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent && percent % 10 == 0) {
                                lastPercent = percent
                                onProgress(percent)
                                onLog("⏳ Downloading: $percent% (${downloaded / 1024 / 1024}MB / ${totalBytes / 1024 / 1024}MB)")
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "downloadFile failed for $urlStr: ${e.message}")
            false
        }
    }

    /**
     * Resilient TAR.GZ streaming extractor with GNU LongLink & Hardlink support
     */
    private fun extractTarGz(tarGzFile: File, destDir: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile), 32768)).use { gzipIn ->
            val headerBuf = ByteArray(512)
            var nextLongName: String? = null
            var nextLongLinkTarget: String? = null

            while (true) {
                var bytesRead = 0
                while (bytesRead < 512) {
                    val r = gzipIn.read(headerBuf, bytesRead, 512 - bytesRead)
                    if (r == -1) break
                    bytesRead += r
                }
                if (bytesRead < 512) break
                if (headerBuf.all { it == 0.toByte() }) break // End of archive (EOF)

                val rawName = String(headerBuf, 0, 100)
                val nameNull = rawName.indexOf('\u0000')
                val rawCleanName = if (nameNull != -1) rawName.substring(0, nameNull).trim() else rawName.trim()
                val entryName = (nextLongName ?: rawCleanName).removePrefix("./").removePrefix("/")
                nextLongName = null

                val sizeStr = String(headerBuf, 124, 12).trim('\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val type = headerBuf[156].toInt().toChar()

                if (type == 'L') {
                    // GNU Long Name entry
                    val nameBytes = ByteArray(size.toInt())
                    var r = 0
                    while (r < size) {
                        val read = gzipIn.read(nameBytes, r, size.toInt() - r)
                        if (read == -1) break
                        r += read
                    }
                    nextLongName = String(nameBytes).trim('\u0000', ' ', '\n')
                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) skipFully(gzipIn, padding)
                    continue
                }

                if (type == 'K') {
                    // GNU Long Link Target entry
                    val linkBytes = ByteArray(size.toInt())
                    var r = 0
                    while (r < size) {
                        val read = gzipIn.read(linkBytes, r, size.toInt() - r)
                        if (read == -1) break
                        r += read
                    }
                    nextLongLinkTarget = String(linkBytes).trim('\u0000', ' ', '\n')
                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) skipFully(gzipIn, padding)
                    continue
                }

                if (entryName.isEmpty()) continue

                val outFile = File(destDir, entryName)

                if (type == '5' || entryName.endsWith("/")) {
                    outFile.mkdirs()
                } else if (type == '2') {
                    // Symlink
                    val rawTarget = String(headerBuf, 157, 100)
                    val targetNull = rawTarget.indexOf('\u0000')
                    val rawCleanTarget = if (targetNull != -1) rawTarget.substring(0, targetNull).trim() else rawTarget.trim()
                    val linkTarget = nextLongLinkTarget ?: rawCleanTarget
                    nextLongLinkTarget = null

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
                } else if (type == '1') {
                    // Hard link
                    val rawTarget = String(headerBuf, 157, 100)
                    val targetNull = rawTarget.indexOf('\u0000')
                    val rawCleanTarget = if (targetNull != -1) rawTarget.substring(0, targetNull).trim() else rawTarget.trim()
                    val linkTarget = (nextLongLinkTarget ?: rawCleanTarget).removePrefix("./").removePrefix("/")
                    nextLongLinkTarget = null

                    val existing = File(destDir, linkTarget)
                    outFile.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.deleteIfExists(outFile.toPath()) }
                    if (existing.exists()) {
                        runCatching {
                            java.nio.file.Files.copy(existing.toPath(), outFile.toPath())
                        }
                    }
                } else if (type == '0' || type == '\u0000') {
                    // Regular file
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var remaining = size
                        val fileBuf = ByteArray(16384)
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

                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) skipFully(gzipIn, padding)
                } else {
                    if (size > 0) {
                        val toSkip = size + ((512 - (size % 512)) % 512)
                        skipFully(gzipIn, toSkip)
                    }
                }
            }
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var skipped = 0L
        while (skipped < bytesToSkip) {
            val s = input.skip(bytesToSkip - skipped)
            if (s <= 0) {
                if (input.read() == -1) break
                skipped += 1
            } else {
                skipped += s
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

        // Deploy smart agy & apt wrappers so commands give clear feedback
        setupAgyWrapper(rootfsDir)
        setupAptWrapper(rootfsDir)

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
        cmd.addAll(listOf("-w", "/root", shell, "-i"))

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
            environment()["USER"] = "root"
            // Crucial: remove Android host environment variables so Python platformdirs/pip treats environment as Linux
            environment().remove("ANDROID_DATA")
            environment().remove("ANDROID_ROOT")
            environment().remove("ANDROID_STORAGE")
            environment().remove("ANDROID_ART_ROOT")
            environment().remove("ANDROID_I18N_ROOT")
            environment().remove("ANDROID_TZDATA_ROOT")

            // /usr/local/bin is FIRST so our agy wrapper is always found before the real binary
            environment()["PATH"] = "/usr/local/bin:/usr/bin:/root/.local/bin:/bin:/sbin:/usr/sbin"
            environment()["LANG"] = "C.UTF-8"
            environment()["PYTHONUNBUFFERED"] = "1"
            environment()["PYTHONIOENCODING"] = "utf-8"
        }
        return pb.start()
    }

    /**
     * Creates a smart agy wrapper at /usr/local/bin/agy inside rootfs.
     */
    private fun setupAgyWrapper(rootfsDir: File) {
        try {
            val wrapperDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
            val wrapper = File(wrapperDir, "agy")
            val script = """
                #!/bin/sh
                # Smart agy wrapper — handles auth + avoids PTY hang in Android terminal
                AGY_REAL="/root/.local/bin/agy"

                if [ ! -f "${'$'}AGY_REAL" ]; then
                    echo "❌ Antigravity CLI install nahi hai."
                    echo "💡 Install karo: curl -fsSL https://antigravity.google/cli/install.sh | bash"
                    exit 1
                fi

                # Pass-through: if any args given, run directly (agy --version, agy auth login, etc.)
                if [ ${'$'}# -gt 0 ]; then
                    exec "${'$'}AGY_REAL" "${'$'}@"
                fi

                # Check if authenticated (config file exists with token)
                AGY_CONFIG="${'$'}{HOME}/.config/antigravity/config.json"
                IS_AUTHED=false
                if [ -f "${'$'}AGY_CONFIG" ]; then
                    if grep -q "access_token\|refresh_token\|id_token" "${'$'}AGY_CONFIG" 2>/dev/null; then
                        IS_AUTHED=true
                    fi
                fi

                # Show menu
                echo ""
                echo "╔══════════════════════════════════════════╗"
                echo "║      🤖 Google Antigravity CLI           ║"
                echo "╚══════════════════════════════════════════╝"

                if [ "${'$'}IS_AUTHED" = "false" ]; then
                    echo ""
                    echo "⚠️  Google account se login nahi hai!"
                    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                    echo "🔗 Login karne ke liye Google OAuth URL generate ho raha hai..."
                    echo ""
                    "${'$'}AGY_REAL" auth login --dangerously-skip-permissions
                    echo ""
                    echo "✅ Upar wala link Chrome mein kholo → Login karo → Wapas ao"
                    echo "   Phir dobara 'agy' type karo task ke liye."
                    exit 0
                fi

                echo ""
                echo "✅ Google account se connected hai"
                echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                printf "📝 Task darj karein: "
                read -r user_prompt
                if [ -n "${'$'}user_prompt" ]; then
                    echo ""
                    echo "⚡ Chal raha hai..."
                    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                    exec "${'$'}AGY_REAL" -p "${'$'}user_prompt" --dangerously-skip-permissions
                else
                    echo "❌ Koi task nahi diya — cancelled."
                fi
            """.trimIndent()
            wrapper.writeText(script)
            wrapper.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "setupAgyWrapper failed: ${e.message}")
        }
    }

    /**
     * Creates smart apt / apt-get wrappers at /usr/local/bin/ inside rootfs
     * so that 'apt-get update' explicitly prints a completion confirmation
     * instead of silently stopping after 'Reading package lists...'.
     */
    private fun setupAptWrapper(rootfsDir: File) {
        try {
            val wrapperDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
            val aptGetWrapper = File(wrapperDir, "apt-get")
            val aptWrapper = File(wrapperDir, "apt")

            val scriptAptGet = """
                #!/bin/sh
                REAL_BIN="/usr/bin/apt-get"
                if [ "${'$'}1" = "update" ]; then
                    "${'$'}REAL_BIN" "${'$'}@"
                    RET=${'$'}?
                    if [ ${'$'}RET -eq 0 ]; then
                        echo "✅ Reading package lists... Done! (All package indexes up to date)"
                    fi
                    exit ${'$'}RET
                else
                    exec "${'$'}REAL_BIN" "${'$'}@"
                fi
            """.trimIndent()

            val scriptApt = """
                #!/bin/sh
                REAL_BIN="/usr/bin/apt"
                if [ "${'$'}1" = "update" ]; then
                    "${'$'}REAL_BIN" "${'$'}@"
                    RET=${'$'}?
                    if [ ${'$'}RET -eq 0 ]; then
                        echo "✅ Reading package lists... Done! (All package indexes up to date)"
                    fi
                    exit ${'$'}RET
                else
                    exec "${'$'}REAL_BIN" "${'$'}@"
                fi
            """.trimIndent()

            aptGetWrapper.writeText(scriptAptGet)
            aptGetWrapper.setExecutable(true, false)
            aptWrapper.writeText(scriptApt)
            aptWrapper.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "setupAptWrapper failed: ${e.message}")
        }
    }
}
