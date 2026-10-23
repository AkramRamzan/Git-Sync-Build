package com.aro.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aro.code.ai.DiffLineType
import com.aro.code.ai.DiffPreviewBlock

/**
 * Module 4 – Interactive Diff Preview Dialog.
 * Shows color-coded additions (green) and deletions (red)
 * with Apply and Reject controls.
 */
@Composable
fun DiffPreviewDialog(
    diff: DiffPreviewBlock,
    onApply: (DiffPreviewBlock) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Proposed Code Change", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = diff.filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    items(diff.lines) { line ->
                        val (bgColor, prefix, textColor) = when (line.type) {
                            DiffLineType.ADD -> Triple(
                                Color(0x334CAF50),
                                "+ ",
                                Color(0xFF2E7D32)
                            )
                            DiffLineType.REMOVE -> Triple(
                                Color(0x33F44336),
                                "- ",
                                Color(0xFFC62828)
                            )
                            DiffLineType.SAME -> Triple(
                                Color.Transparent,
                                "  ",
                                MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "$prefix${line.text}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(diff) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Apply Change")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reject")
            }
        }
    )
}
