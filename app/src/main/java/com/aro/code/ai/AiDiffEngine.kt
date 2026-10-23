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
        """<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n>>>>>>> REPLACE""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )

    /** Parses all SEARCH/REPLACE blocks from a model response. */
    fun parseBlocks(modelResponse: String): List<SearchReplace> =
        BLOCK_REGEX.findAll(modelResponse).map {
            SearchReplace(
                search  = it.groupValues[1],
                replace = it.groupValues[2]
            )
        }.toList()

    /**
     * Applies [blocks] sequentially to [original] source text.
     * Returns [Result.failure] if any SEARCH string is not found.
     */
    fun applyPatches(original: String, blocks: List<SearchReplace>): Result<String> =
        runCatching {
            blocks.fold(original) { acc, (search, replace) ->
                require(acc.contains(search)) {
                    "SEARCH block not found in file:\n$search"
                }
                acc.replaceFirst(search, replace)
            }
        }
}
