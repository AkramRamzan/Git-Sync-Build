package com.aro.code.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 5 – AST Syntax Validator.
 *
 * Validates post-patch code structure before committing.
 * Runs structural bracket, quote, and delimiter integrity checks
 * to ensure AI-generated patches do not corrupt source files.
 */
@Singleton
class SyntaxValidator @Inject constructor() {

    /**
     * Returns true if [source] passes syntax structure checks for [language].
     */
    suspend fun validate(source: String, language: String): Boolean =
        withContext(Dispatchers.Default) {
            try {
                validateSyntax(source, language)
            } catch (e: Throwable) {
                fallbackBracketCheck(source)
            }
        }

    private fun validateSyntax(source: String, language: String): Boolean {
        // Enforce structural bracket & parenthesis balance
        if (!fallbackBracketCheck(source)) return false

        // Language-specific heuristics
        when (language.lowercase()) {
            "kotlin", "java" -> {
                // Ensure balanced comment blocks
                val openComments = "/*".toRegex().findAll(source).count()
                val closeComments = "*/".toRegex().findAll(source).count()
                if (openComments != closeComments) return false
            }
        }
        return true
    }

    /** Robust structural bracket and parenthesis validator that ignores comments and literals. */
    private fun fallbackBracketCheck(source: String): Boolean {
        var depth = 0
        var parenDepth = 0
        var bracketDepth = 0

        val n = source.length
        var i = 0

        while (i < n) {
            val c = source[i]

            // 1. Line comments: // ...
            if (c == '/' && i + 1 < n && source[i + 1] == '/') {
                i += 2
                while (i < n && source[i] != '\n') i++
                continue
            }

            // 2. Block comments: /* ... */
            if (c == '/' && i + 1 < n && source[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) i++
                i += 2
                continue
            }

            // 3. Kotlin Triple-quoted strings: """..."""
            // Skip ALL characters inside including ${...} interpolation blocks so
            // braces/brackets inside raw strings don't corrupt the delimiter counters.
            if (c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                i += 3
                // Scan until closing """ — handle nested ${...} to avoid premature exit
                var interpDepth = 0
                while (i < n) {
                    val tc = source[i]
                    when {
                        // Nested ${...} interpolation — skip so closing } won't confuse us
                        tc == '$' && i + 1 < n && source[i + 1] == '{' -> {
                            interpDepth++
                            i += 2
                        }
                        tc == '}' && interpDepth > 0 -> {
                            interpDepth--
                            i++
                        }
                        // Closing triple-quote only valid outside interpolation block
                        interpDepth == 0 && tc == '"' && i + 2 < n &&
                            source[i + 1] == '"' && source[i + 2] == '"' -> {
                            i += 3
                            break
                        }
                        else -> i++
                    }
                }
                continue
            }

            // 4. Regular double-quoted string: "..."
            if (c == '"') {
                i++
                while (i < n && source[i] != '"') {
                    if (source[i] == '\\' && i + 1 < n) i++ // skip escaped char
                    i++
                }
                if (i < n && source[i] == '"') i++
                continue
            }

            // 5. Single-quoted char literal: '.'
            if (c == '\'') {
                i++
                while (i < n && source[i] != '\'') {
                    if (source[i] == '\\' && i + 1 < n) i++
                    i++
                }
                if (i < n && source[i] == '\'') i++
                continue
            }

            // 6. Count code delimiters
            when (c) {
                '{' -> depth++
                '}' -> if (--depth < 0) return false
                '(' -> parenDepth++
                ')' -> if (--parenDepth < 0) return false
                '[' -> bracketDepth++
                ']' -> if (--bracketDepth < 0) return false
            }
            i++
        }
        return depth == 0 && parenDepth == 0 && bracketDepth == 0
    }
}
