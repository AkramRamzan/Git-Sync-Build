package com.aro.code.ai

import javax.inject.Inject
import javax.inject.Singleton

data class SearchReplace(val search: String, val replace: String)

/**
 * Module 5 – AI Diff Engine.
 * Parses <<<<<<< SEARCH / ======= / >>>>>>> REPLACE blocks produced by the
 * Gemini model and applies them as minimal line-based patches.
 */
@Singleton
class AiDiffEngine @Inject constructor() {
    private val BLOCK_REGEX = Regex(
        """<<<<<<< SEARCH\r?\n(.*?)\r?\n=======\r?\n(.*?)\r?\n>>>>>>> REPLACE""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )

    /** Parses all SEARCH/REPLACE blocks from a model response. */
    fun parseBlocks(modelResponse: String): List<SearchReplace> {
        val normalizedResponse = modelResponse.replace("\r\n", "\n")
        return BLOCK_REGEX.findAll(normalizedResponse).map {
            SearchReplace(
                search  = it.groupValues[1],
                replace = it.groupValues[2]
            )
        }.toList()
    }

    /**
     * Applies [blocks] sequentially to [original] source text.
     * Whitespace-tolerant and line-ending resilient.
     */
    fun applyPatches(original: String, blocks: List<SearchReplace>): Result<String> =
        runCatching {
            val isCrlf = original.contains("\r\n")
            val normalizedOriginal = original.replace("\r\n", "\n")

            val result = blocks.fold(normalizedOriginal) { acc, (searchBlock, replaceBlock) ->
                val normSearch = searchBlock.replace("\r\n", "\n")
                val normReplace = replaceBlock.replace("\r\n", "\n")

                if (acc.contains(normSearch)) {
                    acc.replaceFirst(normSearch, normReplace)
                } else {
                    // Fallback: match ignoring trailing whitespaces per line
                    val searchLines = normSearch.lines().map { it.trimEnd() }
                    val accLines = acc.lines()

                    var matchIndex = -1
                    for (i in 0..accLines.size - searchLines.size) {
                        val window = accLines.subList(i, i + searchLines.size).map { it.trimEnd() }
                        if (window == searchLines) {
                            matchIndex = i
                            break
                        }
                    }

                    if (matchIndex != -1) {
                        val before = accLines.subList(0, matchIndex)
                        val after = accLines.subList(matchIndex + searchLines.size, accLines.size)
                        val replaceLines = normReplace.lines()
                        (before + replaceLines + after).joinToString("\n")
                    } else {
                        error("SEARCH block not found in file:\n$normSearch")
                    }
                }
            }

            if (isCrlf) result.replace("\n", "\r\n") else result
        }
}
