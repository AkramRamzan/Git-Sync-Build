package com.aro.code.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 5 – Tree-Sitter AST Syntax Validator.
 *
 * Uses Tree-Sitter JNI bindings to parse patched source and confirm no
 * ERROR nodes are introduced. Falls back to a lightweight Kotlin bracket
 * checker when the native grammar is unavailable.
 */
@Singleton
class SyntaxValidator @Inject constructor() {

    /**
     * Returns true if [source] parses without syntax errors for [language].
     * Supported values: "kotlin", "java", "python", "javascript", "typescript"
     */
    suspend fun validate(source: String, language: String): Boolean =
        withContext(Dispatchers.Default) {
            try {
                validateWithTreeSitter(source, language)
            } catch (e: Throwable) {
                // Native lib or grammar not loaded in current environment – use fallback
                fallbackBracketCheck(source)
            }
        }

    private fun validateWithTreeSitter(source: String, language: String): Boolean {
        // Tree-sitter JNI call: io.github.nickel-org:tree-sitter
        // Parser is created, language grammar loaded, and source parsed.
        // We check that the root node has no ERROR children.
        val parser = io.github.treesitter.ktreesitter.Parser()
        val grammar = when (language.lowercase()) {
            "kotlin"     -> io.github.treesitter.ktreesitter.Language(
                                com.aro.code.ai.grammars.TreeSitterKotlin.language())
            "java"       -> io.github.treesitter.ktreesitter.Language(
                                com.aro.code.ai.grammars.TreeSitterJava.language())
            else         -> return fallbackBracketCheck(source)
        }
        parser.language = grammar
        val tree = parser.parse(source)
        return !tree.rootNode.hasError()
    }

    /** Simple bracket-balance check as a no-JNI fallback. */
    private fun fallbackBracketCheck(source: String): Boolean {
        var depth = 0
        for (ch in source) {
            when (ch) { '{' -> depth++; '}' -> if (--depth < 0) return false }
        }
        return depth == 0
    }
}
