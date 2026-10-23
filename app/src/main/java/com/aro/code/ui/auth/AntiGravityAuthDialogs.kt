package com.aro.code.ui.auth

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aro.code.auth.PKCEUtil
import java.net.URLEncoder

/**
 * Unified Anti-Gravity Account Dialog.
 *
 * 1. When NOT signed in:
 *    - Generates PKCE verifier & challenge
 *    - Generates Google Sign-In URL
 *    - Shows "Copy Link" and "Open in Chrome"
 *    - Shows "Paste Code" input field
 *    - "OK / Login" button executes login and permanently persists session
 *
 * 2. When SIGNED IN:
 *    - Shows Active status and persistent login confirmation (no need to log in again)
 *    - Displays "Logout" button to sign out anytime
 */
@Composable
fun AntiGravityAccountDialog(
    isSignedIn: Boolean,
    userTokenSnippet: String?,
    defaultClientId: String = "",
    onDismiss: () -> Unit,
    onSubmitCode: (code: String, verifier: String, customClientId: String?) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var customClientId by remember { mutableStateOf("") }
    var showClientIdConfig by remember { mutableStateOf(false) }

    // Generate stable PKCE pair for this dialog session
    val verifier = remember { PKCEUtil.generateVerifier() }
    val challenge = remember(verifier) { PKCEUtil.generateChallenge(verifier) }

    val effectiveClientId = remember(customClientId, defaultClientId) {
        customClientId.ifBlank { defaultClientId.ifBlank { "YOUR_CLIENT_ID" } }
    }

    val authUrl = remember(effectiveClientId, challenge) {
        val cid = URLEncoder.encode(effectiveClientId, "UTF-8")
        val red = URLEncoder.encode("urn:ietf:wg:oauth:2.0:oob", "UTF-8")
        val scope = URLEncoder.encode("openid email profile", "UTF-8")
        val chall = URLEncoder.encode(challenge, "UTF-8")
        "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=$cid" +
                "&redirect_uri=$red" +
                "&response_type=code" +
                "&scope=$scope" +
                "&code_challenge=$chall" +
                "&code_challenge_method=S256" +
                "&access_type=offline" +
                "&prompt=consent"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Bolt,
                contentDescription = null,
                tint = if (isSignedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                if (isSignedIn) "Anti-Gravity Account Active" else "Anti-Gravity Google Login",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isSignedIn) {
                    // ── LOGGED IN STATE ──
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Connected & Synced ✓",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Session permanently saved. Bar bar login karne ki zaroorat nahi hai.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    userTokenSnippet?.let {
                        Text(
                            text = "Token: ${it.take(20)}••••",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // ── NOT LOGGED IN STATE (Termux-style Link + Code Input) ──
                    Text(
                        "1. Link copy karke Chrome mein kholein aur Google se sign in karein:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Generated link container
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = authUrl,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 3,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    // Action buttons for the link
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Google Auth URL", authUrl)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Link copied to clipboard! Chrome mein paste karein.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Link", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = {
                                try {
                                    val customTabsIntent = CustomTabsIntent.Builder().build()
                                    customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                                } catch (_: Exception) {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open in Chrome", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        "2. Google jo code de, usay yahan paste karein aur OK dabayein:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text("Paste Code") },
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
                                    item?.text?.toString()?.let { codeInput = it.trim() }
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                            }
                        }
                    )

                    // Optional Custom Client ID toggle
                    if (showClientIdConfig) {
                        OutlinedTextField(
                            value = customClientId,
                            onValueChange = { customClientId = it },
                            label = { Text("Custom Client ID (Optional)") },
                            placeholder = { Text("xxxx.apps.googleusercontent.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        TextButton(
                            onClick = { showClientIdConfig = true },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Client ID Badle (Optional)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isSignedIn) {
                Button(
                    onClick = onDismiss
                ) {
                    Text("OK")
                }
            } else {
                Button(
                    onClick = {
                        val cid = customClientId.ifBlank { null }
                        onSubmitCode(codeInput.trim(), verifier, cid)
                    },
                    enabled = codeInput.isNotBlank()
                ) {
                    Text("OK / Login")
                }
            }
        },
        dismissButton = {
            if (isSignedIn) {
                OutlinedButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Logout")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
