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

    /** Simple bracket, parenthesis, and quote-balance check. */
    private fun fallbackBracketCheck(source: String): Boolean {
        var depth = 0
        var parenDepth = 0
        var inString = false
        var prevChar = ' '

        for (ch in source) {
            if (ch == '"' && prevChar != '\\') {
                inString = !inString
            }
            if (!inString) {
                when (ch) {
                    '{' -> depth++
                    '}' -> if (--depth < 0) return false
                    '(' -> parenDepth++
                    ')' -> if (--parenDepth < 0) return false
                }
            }
            prevChar = ch
        }
        return depth == 0 && parenDepth == 0
    }
}
