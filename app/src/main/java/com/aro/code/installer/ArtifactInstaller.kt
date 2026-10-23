package com.aro.code.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 7 – Authenticated Artifact Extractor & Installer.
 * Downloads the GitHub Actions ZIP artifact, extracts the APK from cache,
 * and installs it via PackageInstaller.Session API.
 */
@Singleton
class ArtifactInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
) {
    /**
     * Downloads [artifactDownloadUrl] using [token], extracts the first .apk
     * entry found in the ZIP, then starts a PackageInstaller session.
     */
    suspend fun downloadAndInstall(
        artifactDownloadUrl: String,
        token: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Verify sideload permission
            require(context.packageManager.canRequestPackageInstalls()) {
                "REQUEST_INSTALL_PACKAGES permission not granted"
            }

            // 2. Download ZIP stream (follows HTTP 302 redirects automatically)
            val responseChannel = httpClient.get(artifactDownloadUrl) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github+json")
            }.bodyAsChannel()

            // 3. Extract APK from ZIP into cache
            val apkFile = File(context.cacheDir, "update.apk")
            ZipInputStream(responseChannel.toInputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk")) {
                        apkFile.outputStream().use { out -> zip.copyTo(out) }
                        break
                    }
                    entry = zip.nextEntry
                }
            }

            require(apkFile.exists() && apkFile.length() > 0) { "APK not found in artifact ZIP" }

            // 4. PackageInstaller session
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, apkFile.length()).use { output ->
                    apkFile.inputStream().copyTo(output)
                    session.fsync(output)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                val pi = android.app.PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pi.intentSender)
            }

            // 5. Clean up cached APK
            apkFile.delete()
        }
    }
}
