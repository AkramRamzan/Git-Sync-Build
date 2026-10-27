package com.aro.code.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * VisualTransformation providing high-performance syntax highlighting for Kotlin,
 * Java, Python, JavaScript, TypeScript, Rust, and C-like languages in the Code Editor.
 *
 * Uses OffsetMapping.Identity to guarantee 1:1 character cursor positioning and zero lag.
 */
class CodeSyntaxVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val builder = AnnotatedString.Builder(raw)

        // 1. Keywords
        KEYWORD_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.SemiBold),
                match.range.first,
                match.range.last + 1
            )
        }

        // 2. Types
        TYPE_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFF4EC9B0)),
                match.range.first,
                match.range.last + 1
            )
        }

        // 3. Numbers
        NUMBER_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFFB5CEA8)),
                match.range.first,
                match.range.last + 1
            )
        }

        // 4. Annotations
        ANNOTATION_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFFDCDCAA)),
                match.range.first,
                match.range.last + 1
            )
        }

        // 5. Strings (overrides keywords & numbers inside string literals)
        STRING_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFFCE9178)),
                match.range.first,
                match.range.last + 1
            )
        }

        // 6. Comments (overrides everything inside comments)
        COMMENT_REGEX.findAll(raw).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFF6A9955)),
                match.range.first,
                match.range.last + 1
            )
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    companion object {
        private val KEYWORD_REGEX = Regex(
            "\\b(val|var|fun|class|interface|object|package|import|return|if|else|when|for|while|do|break|continue|try|catch|finally|throw|new|public|private|protected|internal|override|open|abstract|sealed|data|enum|const|lateinit|by|lazy|in|is|as|this|super|suspend|inline|reified|def|self|lambda|async|await|function|let|export|default|type|struct|impl|mut|fn|match|case|switch|yield|static|final|void|extends|implements|true|false|null|nil|None|True|False)\\b"
        )
        private val TYPE_REGEX = Regex(
            "\\b(String|Int|Boolean|Double|Float|Long|Short|Byte|Char|Unit|Any|List|Map|Set|Array|Collection|Iterable|Sequence|Pair|Triple|Result|StateFlow|Flow|CoroutineScope|Composable|Modifier|Color|Text|View|Activity|Fragment|Context|File|Uri|Exception|Throwable|Integer|Character)\\b"
        )
        private val NUMBER_REGEX = Regex(
            "\\b(0x[0-9a-fA-F]+|0b[01]+|\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFL]?)\\b"
        )
        private val ANNOTATION_REGEX = Regex(
            "@[a-zA-Z0-9_.]+"
        )
        private val STRING_REGEX = Regex(
            "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
        )
        private val COMMENT_REGEX = Regex(
            "//.*|/\\*[\\s\\S]*?\\*/|#.*"
        )
    }
}
