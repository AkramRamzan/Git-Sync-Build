package com.aro.code.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code")      val deviceCode: String,
    @SerialName("user_code")        val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    val interval: Int = 5,
    @SerialName("expires_in")       val expiresIn: Int = 900
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null
)

/**
 * Module 2 – Authentication Repository.
 * Handles Google Sign-In via Credential Manager and GitHub Device Flow.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val store: CredentialStore,
    @Named("github_client_id") private val clientId: String
) {
    // ── Google Login ────────────────────────────────────────────────────────
    suspend fun signInWithGoogle(): Result<String> = runCatching {
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val result = credentialManager.getCredential(context, request)
        val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
        store.saveGoogleIdToken(googleCred.idToken)
        googleCred.idToken
    }

    // ── GitHub Device Flow ──────────────────────────────────────────────────
    suspend fun startGitHubDeviceFlow(): Result<DeviceCodeResponse> = runCatching {
        httpClient.post("https://github.com/login/device/code") {
            parameter("client_id", clientId)
            parameter("scope", "repo,read:user")
            header("Accept", "application/json")
        }.body()
    }

    suspend fun pollGitHubToken(deviceCode: String, intervalSecs: Int): Result<String> =
        runCatching {
            repeat(60) {
                val resp: AccessTokenResponse = httpClient.post(
                    "https://github.com/login/oauth/access_token"
                ) {
                    parameter("client_id", clientId)
                    parameter("device_code", deviceCode)
                    parameter("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    header("Accept", "application/json")
                }.body()

                if (resp.accessToken != null) {
                    store.saveGithubToken(resp.accessToken)
                    return@runCatching resp.accessToken
                }
                delay(intervalSecs * 1_000L)
            }
            error("Device flow timed out")
        }
}
