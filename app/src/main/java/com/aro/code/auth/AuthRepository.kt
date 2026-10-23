package com.aro.code.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
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

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token")  val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token")      val idToken: String? = null,
    @SerialName("expires_in")    val expiresIn: Long? = 3600,
    @SerialName("token_type")    val tokenType: String? = "Bearer",
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

/**
 * Module 2 & Anti-Gravity Authentication Repository.
 * Handles:
 * 1. Web-based Google OAuth via PKCE + Anti-Gravity Callback (https://antigravity.google/oauth-callback).
 * 2. Credential Manager Google Sign-In.
 * 3. GitHub Device Flow.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val store: CredentialStore,
    @Named("github_client_id") private val githubClientId: String,
    @Named("google_client_id") private val googleClientId: String
) {
    companion object {
        const val ANTIGRAVITY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val ANTIGRAVITY_REDIRECT_URI = "https://antigravity.google/oauth-callback"
        const val ANTIGRAVITY_SCOPES = "openid email profile https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs https://www.googleapis.com/auth/aicode"
    }

    // ── Anti-Gravity Google Web OAuth (PKCE + https://antigravity.google/oauth-callback) ──
    fun createGoogleAuthUrl(codeChallenge: String, clientIdOverride: String? = null): String {
        val cid = clientIdOverride?.takeIf { it.isNotBlank() }
            ?: googleClientId.takeIf { it.isNotBlank() }
            ?: ANTIGRAVITY_CLIENT_ID

        val encodedCid = URLEncoder.encode(cid, "UTF-8")
        val encodedRedirect = URLEncoder.encode(ANTIGRAVITY_REDIRECT_URI, "UTF-8")
        val encodedScope = URLEncoder.encode(ANTIGRAVITY_SCOPES, "UTF-8")
        val encodedChallenge = URLEncoder.encode(codeChallenge, "UTF-8")

        return "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=$encodedCid" +
                "&redirect_uri=$encodedRedirect" +
                "&response_type=code" +
                "&scope=$encodedScope" +
                "&code_challenge=$encodedChallenge" +
                "&code_challenge_method=S256" +
                "&access_type=offline" +
                "&prompt=consent"
    }

    suspend fun exchangeGoogleAuthCode(
        code: String,
        codeVerifier: String,
        clientIdOverride: String? = null
    ): Result<GoogleTokenResponse> = runCatching {
        var trimmedCode = code.trim()

        // Extract code query parameter if user pasted full callback URL
        if (trimmedCode.contains("code=")) {
            val extracted = trimmedCode.substringAfter("code=").substringBefore("&")
            trimmedCode = runCatching { java.net.URLDecoder.decode(extracted, "UTF-8") }.getOrDefault(extracted)
        }

        // Direct token detection if user pasted a bearer token directly
        if (trimmedCode.startsWith("ya29.") || trimmedCode.startsWith("Bearer ")) {
            val directToken = trimmedCode.removePrefix("Bearer ").trim()
            store.saveGoogleTokens(accessToken = directToken, refreshToken = null, idToken = null)
            return@runCatching GoogleTokenResponse(accessToken = directToken)
        }

        val cid = clientIdOverride?.takeIf { it.isNotBlank() }
            ?: googleClientId.takeIf { it.isNotBlank() }
            ?: ANTIGRAVITY_CLIENT_ID

        val clientSecret = when {
            cid.startsWith("1071006060591") -> "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"
            cid.startsWith("884354919052")  -> "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"
            cid.startsWith("1098991175725") -> "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"
            else -> ""
        }

        val response: GoogleTokenResponse = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("code", trimmedCode)
                append("client_id", cid)
                if (clientSecret.isNotBlank()) {
                    append("client_secret", clientSecret)
                }
                append("code_verifier", codeVerifier)
                append("grant_type", "authorization_code")
                append("redirect_uri", ANTIGRAVITY_REDIRECT_URI)
            }
        ).body()

        if (response.error != null) {
            error(response.errorDescription ?: response.error)
        }

        val accessToken = response.accessToken ?: error("No access token in response")
        store.saveGoogleTokens(
            accessToken = accessToken,
            refreshToken = response.refreshToken,
            idToken = response.idToken
        )
        response
    }

    // ── Google Credential Manager (Native Fallback) ─────────────────────────
    suspend fun signInWithGoogle(): Result<String> = runCatching {
        val credentialManager = CredentialManager.create(context)
        val cid = googleClientId.takeIf { it.isNotBlank() } ?: ANTIGRAVITY_CLIENT_ID
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(cid)
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
            parameter("client_id", githubClientId)
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
                    parameter("client_id", githubClientId)
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
