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
        var success = false
        try {
            val dest = java.io.File("/storage/emulated/0/Download/server.py")
            dest.parentFile?.mkdirs()
            context.assets.open("server.py").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            success = true
        } catch (e: Exception) {
            Log.w(TAG, "deployServerPy failed for /storage/emulated/0/Download/server.py: ${e.message}")
        }

        try {
            val rootfsDir = getRootfsDir(context)
            if (rootfsDir.exists()) {
                val targets = listOf(
                    File(rootfsDir, "root/Download/server.py"),
                    File(rootfsDir, "storage/Download/server.py")
                )
                for (target in targets) {
                    try {
                        target.parentFile?.mkdirs()
                        context.assets.open("server.py").use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return success
    }

    /**
     * Fixes:
     * "groups: cannot find name for group ID 3003 groups: cannot find name for group ID 9997 groups: cannot find name for group ID 20458"
     *
     * Android assigns supplementary GIDs to the app process (3003 inet, 9997 everybody, app UID + 10000).
     * Inside PRoot glibc's getgroups() / getgrgid() fails when these GIDs are not defined in /etc/group.
     * This replicates Termux proot-distro behavior by populating /etc/group and /etc/passwd with all active Android AIDs.
     */
    fun fixGroupAndPasswd(rootfsDir: File) {
        try {
            val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
            val groupFile = File(etcDir, "group")
            val existing = if (groupFile.exists()) groupFile.readText() else ""
            val sb = StringBuilder()

            // Standard Android AIDs
            val standardAids = listOf(
                3001 to "aid_bt",
                3002 to "aid_bt_net",
                3003 to "aid_inet",
                3004 to "aid_net_raw",
                3005 to "aid_admin",
                9997 to "aid_everybody"
            )
            for ((gid, name) in standardAids) {
                if (!existing.contains(":$gid:") && !sb.contains(":$gid:")) {
                    sb.append("$name:x:$gid:root\n")
                }
            }

            // Dynamic GIDs from host process (/proc/self/status -> Groups: ...)
            try {
                val statusFile = File("/proc/self/status")
                if (statusFile.exists()) {
                    val statusText = statusFile.readText()
                    val groupsLine = statusText.lines().firstOrNull { it.startsWith("Groups:") }
                    if (groupsLine != null) {
                        val gids = groupsLine.substringAfter(":").trim().split(Regex("\\s+"))
                            .mapNotNull { it.toIntOrNull() }
                        for (gid in gids) {
                            if (!existing.contains(":$gid:") && !sb.contains(":$gid:")) {
                                val name = when (gid) {
                                    3003 -> "aid_inet"
                                    9997 -> "aid_everybody"
                                    else -> "aid_$gid"
                                }
                                sb.append("$name:x:$gid:root\n")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fixGroupAndPasswd proc status: ${e.message}")
            }

            // Android UID/GID variations (myUid, myUid + 10000, myUid + 40000)
            try {
                val myUid = android.os.Process.myUid()
                val extraGids = listOf(myUid, myUid + 10000, myUid + 40000)
                for (gid in extraGids) {
                    if (!existing.contains(":$gid:") && !sb.contains(":$gid:")) {
                        sb.append("aid_$gid:x:$gid:root\n")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fixGroupAndPasswd myUid: ${e.message}")
            }

            if (sb.isNotEmpty()) {
                if (groupFile.exists()) {
                    groupFile.appendText(sb.toString())
                } else {
                    groupFile.writeText("root:x:0:\n" + sb.toString())
                }
                Log.i(TAG, "Injected missing Android GIDs into /etc/group:\n$sb")
            } else if (!groupFile.exists()) {
                groupFile.writeText("root:x:0:\n")
            }

            // Ensure root group exists
            if (!groupFile.readText().contains("root:x:0:")) {
                groupFile.writeText("root:x:0:\n" + groupFile.readText())
            }

            // Ensure root user exists in /etc/passwd
            val passwdFile = File(etcDir, "passwd")
            val existingPasswd = if (passwdFile.exists()) passwdFile.readText() else ""
            if (!existingPasswd.contains("root:x:0:0:")) {
                passwdFile.appendText("root:x:0:0:root:/root:/bin/bash\n")
            }

            // Disable bracketed paste mode in inputrc (prevents [?2004h / [?2004l artifacts)
            val rootDir = File(rootfsDir, "root").apply { mkdirs() }
            val inputrc = File(rootDir, ".inputrc")
            if (!inputrc.exists() || !inputrc.readText().contains("enable-bracketed-paste")) {
                inputrc.writeText("set enable-bracketed-paste off\n")
            }
            val etcInputrc = File(etcDir, "inputrc")
            if (!etcInputrc.exists() || !etcInputrc.readText().contains("enable-bracketed-paste")) {
                etcInputrc.writeText("set enable-bracketed-paste off\n")
            }

            // Ensure prompt in /root/.bashrc
            val bashrc = File(rootDir, ".bashrc")
            if (!bashrc.exists()) bashrc.createNewFile()
            val bashrcContent = bashrc.readText()
            if (!bashrcContent.contains("PS1=")) {
                bashrc.appendText("\nexport PS1='root@arocode:~# '\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixGroupAndPasswd error: ${e.message}")
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

    /**
     * Checks if Google OAuth token exists for agy CLI.
     */
    fun isLoggedIn(context: Context): Boolean {
        return try {
            val rootfsDir = getRootfsDir(context)
            val tokenFile = File(rootfsDir, "root/.gemini/antigravity-cli/antigravity-oauth-token")
            val dlToken = File("/storage/emulated/0/Download/antigravity-oauth-token")
            (tokenFile.exists() && tokenFile.length() > 0) || (dlToken.exists() && dlToken.length() > 0)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads current model from ~/.gemini/antigravity-cli/settings.json.
     */
    fun getSelectedModel(context: Context): String {
        return try {
            val rootfsDir = getRootfsDir(context)
            val settingsFile = File(rootfsDir, "root/.gemini/antigravity-cli/settings.json")
            if (settingsFile.exists()) {
                val json = settingsFile.readText()
                val match = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(json)
                match?.groupValues?.get(1) ?: "Gemini 3.8 Flash (High)"
            } else {
                "Gemini 3.8 Flash (High)"
            }
        } catch (_: Exception) {
            "Gemini 3.8 Flash (High)"
        }
    }

    /**
     * Updates model in ~/.gemini/antigravity-cli/settings.json.
     */
    fun updateSelectedModel(context: Context, modelName: String): Boolean {
        return try {
            val rootfsDir = getRootfsDir(context)
            val configDir = File(rootfsDir, "root/.gemini/antigravity-cli").apply { mkdirs() }
            val settingsFile = File(configDir, "settings.json")
            val content = """{"model":"$modelName","trustedWorkspaces":["/root","/storage","/storage/Download"]}"""
            settingsFile.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resets agy authentication so user can re-login.
     */
    fun resetAuth(context: Context): Boolean {
        return try {
            val rootfsDir = getRootfsDir(context)
            val tokenFile = File(rootfsDir, "root/.gemini/antigravity-cli/antigravity-oauth-token")
            if (tokenFile.exists()) tokenFile.delete()
            val dlToken = File("/storage/emulated/0/Download/antigravity-oauth-token")
            if (dlToken.exists()) dlToken.delete()
            true
        } catch (_: Exception) {
            false
        }
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
                    val libsToExtract = listOf("libproot.so")
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

            // 3. Configure DNS (resolv.conf) & Android Group IDs
            val etcDir = File(targetDir, "etc").apply { mkdirs() }
            File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            fixGroupAndPasswd(targetDir)
            onLog("🌐 DNS configured & Android Group IDs mapped.")

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
            File(etcDir, "environment").writeText("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\nLANG=\"C.UTF-8\"\nGLIBC_TUNABLES=\"glibc.pthread.rseq=0\"\n")

            // Unset Android variables in profile & bashrc so python pip / packages run as standard Linux
            val unsetAndroidVars = "\nunset ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT\nexport GLIBC_TUNABLES=\"glibc.pthread.rseq=0\"\n"
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

            // 10. Termux-like full setup: rich shell, aliases, pip, nano, locale, profile.d
            setupTermuxLikeEnvironment(targetDir)
            onLog("🐚 Shell environment configured (aliases, colors, pip, nano).")

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

    data class ProotLaunchConfig(
        val cmd: Array<String>,
        val envp: Array<String>,
        val cwd: String
    )

    /** Builds command & environment arrays for running PRoot inside a real native PTY */
    fun getProotLaunchConfig(context: Context): ProotLaunchConfig {
        val proot = getProotBinary(context)
            ?: throw IllegalStateException("libproot.so not found in nativeLibraryDir!")

        val rootfsDir = getRootfsDir(context)
        val rootfsPath = rootfsDir.absolutePath

        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "root/.local/bin").mkdirs()

        fixGroupAndPasswd(rootfsDir)
        deployServerPy(context)
        setupAgyWrapper(rootfsDir)
        setupAptWrapper(rootfsDir)
        setupTermuxLikeEnvironment(rootfsDir)

        val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
        val shell = if (File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()) "/bin/bash" else "/bin/sh"

        val bindings = mutableListOf(
            "-b", "/dev",
            "-b", "/proc"
        )
        if (File("/sys").canRead()) {
            bindings.add("-b")
            bindings.add("/sys")
        }
        val extStorage = File("/storage/emulated/0")
        if (extStorage.exists() && extStorage.canRead()) {
            bindings.add("-b")
            bindings.add("/storage/emulated/0:/storage")
            val download = File("/storage/emulated/0/Download")
            if (download.exists() && download.canRead()) {
                bindings.add("-b")
                bindings.add("/storage/emulated/0/Download:/root/Download")
            }
        }

        // Ensure guest mountpoints exist
        File(rootfsDir, "storage").mkdirs()
        File(rootfsDir, "root/Download").mkdirs()

        // Ensure shell binaries and dynamic linkers have executable bit set
        listOf(
            "bin/bash", "usr/bin/bash",
            "bin/sh", "usr/bin/sh",
            "bin/env", "usr/bin/env",
            "lib/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        ).forEach { rel ->
            val f = File(rootfsDir, rel)
            if (f.exists()) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }

        val envBinary = if (File(rootfsDir, "usr/bin/env").exists()) "/usr/bin/env" else "/bin/env"
        val cmd = mutableListOf(
            proot.absolutePath,
            "-v", "-1",
            "-r", rootfsPath,
            "-0", // Fake root ID
            "-l", // --link2symlink
            "-L", // Correct symlink sizes
            "--sysvipc", // System V IPC support
            "-k", "6.17.0-PRoot-Distro", // Override kernel release for modern glibc compatibility
            "--kill-on-exit"
        )
        cmd.addAll(bindings)

        cmd.addAll(listOf(
            "-w", "/root",
            shell, "-l"
        ))

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binDir = File(context.filesDir, "bin")

        val envMap = HashMap<String, String>()
        // CRITICAL for Android: Disable seccomp accelerator because Android Zygote already
        // installed an irreversible seccomp filter on the app process.
        envMap["PROOT_VERBOSE"] = "-1"
        envMap["PROOT_NO_SECCOMP"] = "1"
        envMap["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
        envMap["PROOT_TMP_DIR"] = prootTmp.absolutePath
        envMap["TMPDIR"] = prootTmp.absolutePath
        envMap["TEMP"] = prootTmp.absolutePath
        envMap["TMP"] = prootTmp.absolutePath
        envMap["HOME"] = "/root"
        envMap["USER"] = "root"
        envMap["TERM"] = "xterm-256color"
        envMap["COLORTERM"] = "truecolor"
        envMap["LANG"] = "C.UTF-8"
        envMap["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // CRITICAL for Android + Ubuntu 22.04 LTS (glibc 2.35):
        // Disabling rseq via GLIBC_TUNABLES prevents glibc from issuing the rseq() syscall.
        envMap["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"

        // Host system paths for Bionic kernel/linker
        envMap["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        envMap["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"

        // Strip any dangerous host environment variables (static binary needs no LD_LIBRARY_PATH)
        envMap.remove("LD_PRELOAD")
        envMap.remove("LD_LIBRARY_PATH")
        // CRITICAL: Ensure loader is NOT set! Termux PRoot's loader has fixed VA 0x5400000000
        // which collides with Android ART memory space causing SIGSEGV (code 139).
        // Without PROOT_LOADER, PRoot runs cleanly via standard ptrace with zero crashes!
        envMap.remove("PROOT_LOADER")
        envMap.remove("PROOT_LOADER_32")
        envMap.remove("CLASSPATH")
        envMap.remove("DEX2OATBOOTCLASSPATH")
        envMap.remove("BOOTCLASSPATH")

        val envp = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        return ProotLaunchConfig(cmd.toTypedArray(), envp, rootfsPath)
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

        // Fix group IDs (Android supplementary AIDs 3003, 9997, etc.) and passwd
        fixGroupAndPasswd(rootfsDir)

        // Ensure latest server.py is synced
        deployServerPy(context)

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
            "-v", "-1",
            "-r", rootfsPath,
            "-0", // Fake root ID
            "--link2symlink",
            "-L",
            "--sysvipc",
            "-k", "6.17.0-PRoot-Distro",
            "--kill-on-exit"
        )
        cmd.addAll(bindings)
        // Termux-style Real PTY: use Python POSIX PTY so bash runs with real isatty=true, /dev/pts, and full terminal control
        val hasPython = File(rootfsDir, "usr/bin/python3").exists()
        val envBinary = if (File(rootfsDir, "usr/bin/env").exists()) "/usr/bin/env" else "/bin/env"
        if (hasPython) {
            cmd.addAll(listOf("-w", "/root", envBinary, "-i",
                "HOME=/root", "USER=root", "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/usr/bin/python3", "-c", "import pty; pty.spawn(['/bin/bash', '-i'])"))
        } else {
            cmd.addAll(listOf("-w", "/root", envBinary, "-i",
                "HOME=/root", "USER=root", "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                shell, "-i"))
        }

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val binDir = File(context.filesDir, "bin")
            environment()["PROOT_VERBOSE"] = "-1"
            environment()["PROOT_NO_SECCOMP"] = "1"
            environment()["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
            environment()["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            environment()["TMPDIR"] = prootTmp.absolutePath
            environment()["TEMP"] = prootTmp.absolutePath
            environment()["TMP"] = prootTmp.absolutePath
            environment()["TERM"] = "xterm-256color"
            environment()["HOME"] = "/root"
            environment()["USER"] = "root"
            // Crucial: remove Android host environment variables
            environment().remove("ANDROID_DATA")
            environment().remove("ANDROID_ROOT")
            environment().remove("ANDROID_STORAGE")
            environment().remove("ANDROID_ART_ROOT")
            environment().remove("ANDROID_I18N_ROOT")
            environment().remove("ANDROID_TZDATA_ROOT")
            environment().remove("LD_PRELOAD")
            environment().remove("LD_LIBRARY_PATH")
            environment().remove("PROOT_LOADER")
            environment().remove("PROOT_LOADER_32")

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
            // Auto-configure agy settings & onboarding state to skip color scheme & onboarding TUI wizards
            val agyConfigDir = File(rootfsDir, "root/.gemini/antigravity-cli").apply { mkdirs() }
            val settingsFile = File(agyConfigDir, "settings.json")
            if (!settingsFile.exists()) {
                settingsFile.writeText("""{"model":"Gemini 3.8 Flash (High)","trustedWorkspaces":["/root","/storage","/storage/Download"]}""")
            }
            val jetskiFile = File(agyConfigDir, "jetski_state.pbtxt")
            if (!jetskiFile.exists()) {
                jetskiFile.writeText(
                    """
                    post_onboarding: {
                      completed_steps: POST_ONBOARDING_STEP_TYPE_MANAGER_WELCOME
                      completed_steps: POST_ONBOARDING_STEP_TYPE_USAGE_MODE
                      completed_steps: POST_ONBOARDING_STEP_TYPE_AGENT_CONFIGURATION
                      completed_steps: POST_ONBOARDING_STEP_TYPE_ADD_WORKSPACE
                    }
                    """.trimIndent()
                )
            }

            val wrapperDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
            val wrapper = File(wrapperDir, "agy")
            val script = """
                #!/bin/sh
                # Smart agy wrapper — handles auth; terminal size set by real PTY (TIOCSWINSZ)
                AGY_REAL="/root/.local/bin/agy"

                if [ ! -f "${'$'}AGY_REAL" ]; then
                    echo "❌ Antigravity CLI install nahi hai."
                    echo "💡 Install karo: curl -fsSL https://antigravity.google/cli/install.sh | bash"
                    exit 1
                fi

                # Pass-through: if any args given, run directly (agy -p "prompt", agy --continue, etc.)
                if [ ${'$'}# -gt 0 ]; then
                    exec "${'$'}AGY_REAL" "${'$'}@"
                fi

                # Check if authenticated (token file exists and is not empty)
                AGY_TOKEN="${'$'}{HOME}/.gemini/antigravity-cli/antigravity-oauth-token"
                
                # Auto-import token from shared Android Download folder (e.g. copied from Termux)
                if [ ! -s "${'$'}AGY_TOKEN" ]; then
                    if [ -s "/storage/Download/antigravity-oauth-token" ]; then
                        mkdir -p "${'$'}{HOME}/.gemini/antigravity-cli"
                        cp "/storage/Download/antigravity-oauth-token" "${'$'}AGY_TOKEN"
                        chmod 600 "${'$'}AGY_TOKEN"
                        echo "✅ Google auth token auto-imported from /storage/Download/!"
                    elif [ -s "/root/Download/antigravity-oauth-token" ]; then
                        mkdir -p "${'$'}{HOME}/.gemini/antigravity-cli"
                        cp "/root/Download/antigravity-oauth-token" "${'$'}AGY_TOKEN"
                        chmod 600 "${'$'}AGY_TOKEN"
                        echo "✅ Google auth token auto-imported from /root/Download/!"
                    fi
                fi

                IS_AUTHED=false
                if [ -s "${'$'}AGY_TOKEN" ]; then
                    IS_AUTHED=true
                fi

                exec "${'$'}AGY_REAL"
            """.trimIndent()
            wrapper.writeText(script)
            wrapper.setExecutable(true, false)

            // Native Termux-style /model command script
            val modelScript = File(wrapperDir, "model")
            modelScript.writeText("""
                #!/bin/sh
                SETTINGS="${'$'}HOME/.gemini/antigravity-cli/settings.json"
                CURRENT=${'$'}(grep -o '"model"[^,]*' "${'$'}SETTINGS" 2>/dev/null | cut -d'"' -f4)
                [ -z "${'$'}CURRENT" ] && CURRENT="Gemini 3.8 Flash (High)"

                if [ -n "${'$'}1" ]; then
                    case "${'$'}1" in
                        1|*3.8*|*flash-high*) NEW_MODEL="Gemini 3.8 Flash (High)" ;;
                        2|*2.5-pro*|*pro*)   NEW_MODEL="gemini-2.5-pro" ;;
                        3|*2.5-flash*|*flash*) NEW_MODEL="gemini-2.5-flash" ;;
                        4|*2.0-flash*)       NEW_MODEL="gemini-2.0-flash" ;;
                        5|*claude*|*sonnet*) NEW_MODEL="Claude 3.5 Sonnet" ;;
                        *)                   NEW_MODEL="${'$'}1" ;;
                    esac
                    mkdir -p "${'$'}HOME/.gemini/antigravity-cli"
                    echo "{\"model\":\"${'$'}NEW_MODEL\",\"trustedWorkspaces\":[\"/root\",\"/storage\",\"/storage/Download\"]}" > "${'$'}SETTINGS"
                    printf "\033[1;32m✅ Active Model updated to:\033[0m \033[1;36m%s\033[0m\n\n" "${'$'}NEW_MODEL"
                    exit 0
                fi

                printf "\033[1;36m╔══════════════════════════════════════════════════════╗\033[0m\n"
                printf "\033[1;36m║\033[0m             \033[1;35m🧠 Available AI Models\033[0m                   \033[1;36m║\033[0m\n"
                printf "\033[1;36m╚══════════════════════════════════════════════════════╝\033[0m\n"
                printf " Current Active Model: \033[1;32m● %s\033[0m\n\n" "${'$'}CURRENT"
                printf " [1] \033[1;33mGemini 3.8 Flash (High)\033[0m   - Ultra-fast & Advanced (Recommended)\n"
                printf " [2] \033[1;34mgemini-2.5-pro\033[0m            - Deep Reasoning & Complex Coding\n"
                printf " [3] \033[1;36mgemini-2.5-flash\033[0m          - Ultra Fast & Responsive\n"
                printf " [4] \033[1;32mgemini-2.0-flash\033[0m          - Stable Generation\n"
                printf " [5] \033[1;35mClaude 3.5 Sonnet\033[0m         - High-Precision Coding\n\n"
                printf "\033[0;37mTip: To switch model, type:\033[0m \033[1;32m/model <1-5 or name>\033[0m\n"
                printf "\033[0;37mExample:\033[0m \033[1;36m/model 2\033[0m  or  \033[1;36m/model gemini-2.5-pro\033[0m\n\n"
            """.trimIndent() + "\n")
            modelScript.setExecutable(true, false)
            try {
                val rootModel = File(rootfsDir, "model")
                rootModel.writeText(modelScript.readText())
                rootModel.setExecutable(true, false)
            } catch (_: Exception) {}

            val loginHelper = File(wrapperDir, "agy_login.py")
            val pyScript = """
                import pty, os, time, sys, select, re

                def main():
                    master, slave = pty.openpty()
                    pid = os.fork()
                    if pid == 0:
                        os.close(master)
                        os.setsid()
                        try:
                            import fcntl, termios
                            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                        except:
                            pass
                        os.dup2(slave, 0)
                        os.dup2(slave, 1)
                        os.dup2(slave, 2)
                        os.close(slave)
                        os.environ['TERM'] = 'xterm-256color'
                        os.execv('/root/.local/bin/agy', ['/root/.local/bin/agy'])
                    else:
                        os.close(slave)
                        buf = b''
                        while b'Navigate' not in buf:
                            try:
                                chunk = os.read(master, 512)
                                if not chunk: break
                                buf += chunk
                            except: break

                        time.sleep(0.3)
                        os.write(master, b'\r\n')

                        url = None
                        for _ in range(25):
                            r, _, _ = select.select([master], [], [], 1)
                            if not r: continue
                            data = os.read(master, 2048)
                            if not data: break
                            text = data.decode('utf-8', errors='replace')
                            m = re.search(r'https://accounts\.google\.com/o/oauth2/auth\?[^\x1b\x07\s]+', text)
                            if m:
                                url = m.group(0)
                                break

                        if url:
                            print("\n╔══════════════════════════════════════════════════════════════╗")
                            print("║ 🔗 GOOGLE LOGIN URL:                                         ║")
                            print("╚══════════════════════════════════════════════════════════════╝\n")
                            print(url)
                            try:
                                with open('/storage/Download/google_login.html', 'w') as f:
                                    f.write(f'<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=\'{url}\'\"></head><body><h2>Google Login</h2><p><a href=\"{url}\">Click here to login</a></p></body></html>')
                                print("\n💾 Direct 1-tap file: /storage/Download/google_login.html")
                            except:
                                pass
                            print("\n👉 Upar '1-Tap Google Login' button dabayein ya Chrome me open karein.")
                        else:
                            print("❌ Login link generate nahi ho saka. Termux se token copy karein:")
                            print("   cp ~/.gemini/antigravity-cli/antigravity-oauth-token /sdcard/Download/")

                        os.close(master)
                        try: os.kill(pid, 9)
                        except: pass

                if __name__ == '__main__':
                    main()
            """.trimIndent()
            loginHelper.writeText(pyScript)
            loginHelper.setExecutable(true, false)
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

    /**
     * Configures the Ubuntu environment to behave like Termux:
     * - Rich colorful PS1 prompt
     * - Common aliases (ll, la, .., update, install, etc.)
     * - pip.conf (no cache, user-install friendly)
     * - nano with syntax highlighting
     * - profile.d welcome script
     * - locale set to C.UTF-8
     * - /usr/local/bin in PATH for all login/non-login shells
     */
    private fun setupTermuxLikeEnvironment(rootfsDir: File) {
        try {
            val rootDir = File(rootfsDir, "root").apply { mkdirs() }
            val etcDir = File(rootfsDir, "etc").apply { mkdirs() }

            // ── 1. Rich .bashrc (overwrite to canonical state) ───────────────────────
            val bashrc = File(rootDir, ".bashrc")
            bashrc.writeText("""
                # ~/.bashrc — AroCode Ubuntu Shell  (Termux-like)
                # ─────────────────────────────────────────────────

                # If not running interactively, do nothing
                case "${'$'}-" in
                  *i*) ;;
                  *) return;;
                esac

                # Unset Android host variables that confuse Python/pip
                unset ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT \
                      ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT

                # ── GLIBC Tunables (Disable rseq to eliminate Android Signal 31 SIGSYS) ──
                export GLIBC_TUNABLES="glibc.pthread.rseq=0"

                # ── PATH ──
                export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin"

                # ── Locale ──
                export LANG="C.UTF-8"
                export LC_ALL="C.UTF-8"

                # ── Terminal colours ──
                export TERM="xterm-256color"
                export COLORTERM="truecolor"

                # ── Python ──
                export PYTHONUNBUFFERED=1
                export PYTHONIOENCODING=utf-8

                # ── Colorful prompt (Termux-style) ──
                # green user@host, blue cwd, white #
                export PS1='\[\e[0;32m\]root@arocode\[\e[0m\]:\[\e[0;34m\]\w\[\e[0m\]\# '

                # ── LS colours ──
                export LS_COLORS='di=34:ln=36:so=32:pi=33:ex=31:bd=34;46:cd=34;43:su=30;41:sg=30;46:tw=30;42:ow=34;42'
                alias ls='ls --color=auto'
                alias ll='ls -alF --color=auto'
                alias la='ls -A --color=auto'
                alias l='ls -CF --color=auto'

                # ── Navigation ──
                alias ..='cd ..'
                alias ...='cd ../..'
                alias ~='cd ~'

                # ── APT shortcuts (Termux-like) ──
                alias update='apt-get update'
                alias upgrade='apt-get upgrade -y'
                alias install='apt-get install -y'
                alias remove='apt-get remove -y'
                alias search='apt-cache search'
                alias show='apt-cache show'

                # ── Python shortcuts ──
                alias python='python3'
                alias pip='pip3'

                # ── Git shortcuts ──
                alias gs='git status'
                alias gd='git diff'
                alias gl='git log --oneline -10'
                alias ga='git add'
                alias gc='git commit'
                alias gp='git push'

                # ── Misc ──
                alias cls='clear'
                alias h='history'
                alias df='df -h'
                alias du='du -sh'
                alias free='free -h'
                alias ps='ps aux'
                alias top='htop 2>/dev/null || top'
                alias ports='ss -tulnp'
                alias myip='curl -s ifconfig.me 2>/dev/null || echo "No network"'
                alias /model='model'
                alias model='/usr/local/bin/model'

                # ── History ──
                HISTSIZE=2000
                HISTFILESIZE=4000
                HISTCONTROL=ignoredups:erasedups
                shopt -s histappend

                # ── Grep colours ──
                alias grep='grep --color=auto'
                alias egrep='egrep --color=auto'
                alias fgrep='fgrep --color=auto'

                # ── Better diffs ──
                alias diff='diff --color=auto'

                # ── Safety ──
                alias rm='rm -i'
                alias cp='cp -i'
                alias mv='mv -i'

                # ── Enable bash completion if available ──
                if [ -f /etc/bash_completion ] && ! shopt -oq posix; then
                    . /etc/bash_completion
                fi
            """.trimIndent() + "\n")

            // ── 2. .bash_profile (login shells) ──────────────────────────────────────
            val bashProfile = File(rootDir, ".bash_profile")
            bashProfile.writeText("""
                # ~/.bash_profile — login shell entry point
                [ -f ~/.bashrc ] && source ~/.bashrc
                [ -f ~/.profile ] && source ~/.profile
            """.trimIndent() + "\n")

            // ── 3. /etc/profile.d/arocode.sh (system-wide; sourced by /etc/profile) ──
            val profileD = File(etcDir, "profile.d").apply { mkdirs() }
            val sysProfile = File(profileD, "arocode.sh")
            sysProfile.writeText("""
                #!/bin/sh
                # AroCode Ubuntu — system-wide environment (profile.d)
                export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin"
                export LANG="C.UTF-8"
                export LC_ALL="C.UTF-8"
                export TERM="xterm-256color"
                export COLORTERM="truecolor"
                export PYTHONUNBUFFERED=1
                export PYTHONIOENCODING=utf-8
                export GLIBC_TUNABLES="glibc.pthread.rseq=0"
                unset ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT \
                      ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT
            """.trimIndent() + "\n")
            sysProfile.setExecutable(true, false)

            // ── 4. /etc/environment (non-login, non-interactive path/locale) ─────────
            File(etcDir, "environment").writeText(
                "PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin\"\n" +
                "LANG=\"C.UTF-8\"\n" +
                "LC_ALL=\"C.UTF-8\"\n" +
                "GLIBC_TUNABLES=\"glibc.pthread.rseq=0\"\n"
            )

            // ── 5. pip.conf — no cache, no root warning, allow user-install ──────────
            val pipDir = File(rootDir, ".pip").apply { mkdirs() }
            File(pipDir, "pip.conf").writeText("""
                [global]
                no-cache-dir = true
                disable-pip-version-check = true
                break-system-packages = true
                timeout = 60
                [install]
                user = false
            """.trimIndent() + "\n")

            // Also write system-level pip.conf
            val etcPipDir = File(etcDir, "pip").apply { mkdirs() }
            File(etcPipDir, "pip.conf").writeText("""
                [global]
                no-cache-dir = true
                disable-pip-version-check = true
                break-system-packages = true
                timeout = 60
            """.trimIndent() + "\n")

            // ── 6. nano config — sane defaults with syntax highlighting ──────────────
            val nanorc = File(rootDir, ".nanorc")
            nanorc.writeText("""
                set smooth
                set autoindent
                set softwrap
                set tabsize 4
                set tabstospaces
                set linenumbers
                set mouse
                set constantshow
                set historylog
                # Include syntax files if available
                include "/usr/share/nano/*.nanorc"
            """.trimIndent() + "\n")

            // ── 7. .inputrc — disable bracketed-paste artifacts, VI mode optional ────
            val inputrc = File(rootDir, ".inputrc")
            if (!inputrc.exists() || inputrc.readText().isBlank()) {
                inputrc.writeText("""
                    set enable-bracketed-paste off
                    set bell-style none
                    set completion-ignore-case on
                    set show-all-if-ambiguous on
                    "\e[A": history-search-backward
                    "\e[B": history-search-forward
                """.trimIndent() + "\n")
            }

            // ── 8. /etc/inputrc — system-wide readline ───────────────────────────────
            val etcInputrc = File(etcDir, "inputrc")
            if (!etcInputrc.exists() || etcInputrc.readText().isBlank()) {
                etcInputrc.writeText("""
                    set enable-bracketed-paste off
                    set bell-style none
                    set completion-ignore-case on
                """.trimIndent() + "\n")
            }

            // ── 9. /etc/wgetrc — default wget options ─────────────────────────────────
            File(etcDir, "wgetrc").writeText("""
                # AroCode wget defaults
                timeout = 60
                tries = 3
                continue = on
                progress = bar:force
            """.trimIndent() + "\n")

            // ── 10. motd — Clean (no watermark) ─────────────────────────────────────
            val motd = File(etcDir, "motd")
            motd.writeText("")

            // ── 11. /usr/local/bin/help — quick-start helper ──────────────────────────
            val wrapperDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
            val helpCmd = File(wrapperDir, "help")
            helpCmd.writeText("""
                #!/bin/sh
                echo ""
                echo "╔═══════════════════════════════════════════════════╗"
                echo "║  🐧 AroCode Ubuntu — Quick Reference              ║"
                echo "╠═══════════════════════════════════════════════════╣"
                echo "║  Package management:                               ║"
                echo "║    apt update           → Update package lists     ║"
                echo "║    apt install <pkg>    → Install a package        ║"
                echo "║    apt remove <pkg>     → Remove a package         ║"
                echo "║    apt search <term>    → Search packages          ║"
                echo "╠═══════════════════════════════════════════════════╣"
                echo "║  Python:                                           ║"
                echo "║    python3 script.py    → Run a script             ║"
                echo "║    pip install <pkg>    → Install Python package   ║"
                echo "╠═══════════════════════════════════════════════════╣"
                echo "║  Antigravity CLI:                                  ║"
                echo "║    agy                  → Start AI coding session  ║"
                echo "║    agy -p 'prompt'      → One-shot AI prompt       ║"
                echo "╠═══════════════════════════════════════════════════╣"
                echo "║  Server:                                           ║"
                echo "║    python3 /storage/Download/server.py             ║"
                echo "╚═══════════════════════════════════════════════════╝"
                echo ""
            """.trimIndent() + "\n")
            helpCmd.setExecutable(true, false)

            Log.i(TAG, "setupTermuxLikeEnvironment: complete")
        } catch (e: Exception) {
            Log.w(TAG, "setupTermuxLikeEnvironment failed: ${e.message}")
        }
    }
}
