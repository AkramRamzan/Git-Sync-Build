package com.aro.code.ui.auth

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aro.code.auth.AuthRepository
import com.aro.code.auth.PKCEUtil
import kotlinx.coroutines.launch

/**
 * Out-of-band Code Input Dialog.
 * User pastes the authorization code returned by Google after web sign-in.
 */
@Composable
fun CodeInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Anti-Gravity Authorization") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste the authorization code displayed on the Google sign-in page to complete authentication.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Authorization Code") },
                    placeholder = { Text("4/0A...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard?.hasPrimaryClip() == true &&
                                clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                            ) {
                                val item = clipboard.primaryClip?.getItemAt(0)
                                item?.text?.toString()?.let { code = it.trim() }
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(code.trim()) },
                enabled = code.isNotBlank()
            ) {
                Text("Confirm Login")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Anti-Gravity Account Dialog / Sheet.
 * Displays Google Auth status and initiates PKCE web sign-in.
 */
@Composable
fun AntiGravityAccountDialog(
    isSignedIn: Boolean,
    userTokenSnippet: String?,
    onDismiss: () -> Unit,
    onStartWebAuth: (url: String, verifier: String) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var customClientId by remember { mutableStateOf("") }
    var showClientIdField by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Anti-Gravity Account") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isSignedIn) {
                    Text(
                        text = "Connected with Anti-Gravity Google Account ✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    userTokenSnippet?.let {
                        Text(
                            text = "Token: ${it.take(12)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Sign in with Google via Anti-Gravity PKCE flow to synchronize code and agent services.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (showClientIdField) {
                        OutlinedTextField(
                            value = customClientId,
                            onValueChange = { customClientId = it },
                            label = { Text("Google OAuth Client ID (Optional)") },
                            placeholder = { Text("apps.googleusercontent.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        TextButton(onClick = { showClientIdField = true }) {
                            Text("Configure Custom Client ID", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isSignedIn) {
                Button(
                    onClick = {
                        val verifier = PKCEUtil.generateVerifier()
                        val challenge = PKCEUtil.generateChallenge(verifier)
                        val cid = customClientId.ifBlank { null }
                        // Construct URL and trigger
                        val url = "https://accounts.google.com/o/oauth2/v2/auth?" +
                                "client_id=${cid ?: "YOUR_CLIENT_ID"}" +
                                "&redirect_uri=urn:ietf:wg:oauth:2.0:oob" +
                                "&response_type=code" +
                                "&scope=openid%20email%20profile" +
                                "&code_challenge=$challenge" +
                                "&code_challenge_method=S256" +
                                "&access_type=offline" +
                                "&prompt=consent"

                        // Try custom tabs or launch browser intent
                        try {
                            val customTabsIntent = CustomTabsIntent.Builder().build()
                            customTabsIntent.launchUrl(context, Uri.parse(url))
                        } catch (e: Exception) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }

                        onStartWebAuth(url, verifier)
                    }
                ) {
                    Text("Sign In with Google")
                }
            } else {
                OutlinedButton(onClick = onSignOut) {
                    Text("Sign Out")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
