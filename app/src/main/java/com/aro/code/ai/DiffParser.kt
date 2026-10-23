package com.aro.code.ai

enum class DiffLineType {
    ADD,
    REMOVE,
    SAME
}

data class DiffLine(
    val type: DiffLineType,
    val text: String
)

data class DiffPreviewBlock(
    val filePath: String,
    val originalSnippet: String,
    val replacementSnippet: String,
    val lines: List<DiffLine>
)

object DiffParser {
    private val SEARCH_REPLACE_REGEX = Regex(
        """(?:(?:FILE|File):\s*([^\n\r]+)\s*)?<<<<<<< SEARCH\r?\n(.*?)\r?\n=======\r?\n(.*?)\r?\n>>>>>>> REPLACE""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val UNIFIED_DIFF_REGEX = Regex(
        """```(?:diff)?\r?\n--- (?:a/)?([^\r\n]+)\r?\n\+\+\+ (?:b/)?([^\r\n]+)\r?\n(.*?)\r?\n```""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parseAllDiffs(text: String, defaultFilePath: String = "ActiveFile.kt"): List<DiffPreviewBlock> {
        val results = mutableListOf<DiffPreviewBlock>()

        // 1. Search/Replace blocks
        SEARCH_REPLACE_REGEX.findAll(text).forEach { match ->
            val file = match.groupValues[1].takeIf { it.isNotBlank() }?.trim() ?: defaultFilePath
            val search = match.groupValues[2]
            val replace = match.groupValues[3]

            val diffLines = mutableListOf<DiffLine>()
            search.lines().forEach { diffLines.add(DiffLine(DiffLineType.REMOVE, it)) }
            replace.lines().forEach { diffLines.add(DiffLine(DiffLineType.ADD, it)) }

            results.add(
                DiffPreviewBlock(
                    filePath = file,
                    originalSnippet = search,
                    replacementSnippet = replace,
                    lines = diffLines
                )
            )
        }

        // 2. Markdown unified diffs
        UNIFIED_DIFF_REGEX.findAll(text).forEach { match ->
            val file = match.groupValues[2].trim()
            val diffBody = match.groupValues[3]

            val diffLines = mutableListOf<DiffLine>()
            val searchSb = StringBuilder()
            val replaceSb = StringBuilder()

            diffBody.lines().forEach { line ->
                when {
                    line.startsWith("+") && !line.startsWith("+++") -> {
                        val content = line.removePrefix("+")
                        diffLines.add(DiffLine(DiffLineType.ADD, content))
                        replaceSb.appendLine(content)
                    }
                    line.startsWith("-") && !line.startsWith("---") -> {
                        val content = line.removePrefix("-")
                        diffLines.add(DiffLine(DiffLineType.REMOVE, content))
                        searchSb.appendLine(content)
                    }
                    line.startsWith("@@") -> {
                        // Hunk header, skip or display as context
                    }
                    else -> {
                        val content = if (line.startsWith(" ")) line.substring(1) else line
                        diffLines.add(DiffLine(DiffLineType.SAME, content))
                        searchSb.appendLine(content)
                        replaceSb.appendLine(content)
                    }
                }
            }

            results.add(
                DiffPreviewBlock(
                    filePath = file,
                    originalSnippet = searchSb.toString().trimEnd(),
                    replacementSnippet = replaceSb.toString().trimEnd(),
                    lines = diffLines
                )
            )
        }

        return results
    }
}
