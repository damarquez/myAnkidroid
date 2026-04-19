package com.ichi2.anki.props

import com.ichi2.anki.libanki.NotetypeJson
import org.json.JSONArray
import org.json.JSONObject

private val PROP_SEARCH_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-prop-search-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class TemplatePropSearchRule(
    val field: String?,
    val deck: String,
    val sourceField: String,
    val insertCloze: Int,
    val searchCloze: Int,
    val matchMode: String,
    val applyMode: String,
    val tokenPattern: String,
    val maxResults: Int,
)

data class ClozeToken(
    val ordinal: Int,
    val text: String,
    val hint: String?,
)

class TemplatePropSearchConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun parseTemplatePropSearchRules(noteType: NotetypeJson): List<TemplatePropSearchRule> {
    val rules = mutableListOf<TemplatePropSearchRule>()
    for (template in noteType.templates) {
        rules += parseTemplatePropSearchRules(template.qfmt)
        rules += parseTemplatePropSearchRules(template.afmt)
    }
    return rules
}

fun parseTemplatePropSearchRules(templateHtml: String): List<TemplatePropSearchRule> =
    PROP_SEARCH_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplatePropSearchRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

fun selectTemplatePropSearchRule(
    rules: List<TemplatePropSearchRule>,
    currentFieldName: String,
): TemplatePropSearchRule? = rules.firstOrNull { it.field == currentFieldName } ?: rules.firstOrNull { it.field == null }

fun parseClozeTokens(input: String): List<ClozeToken> {
    val tokens = mutableListOf<ClozeToken>()
    var index = 0

    while (index < input.length) {
        val openIndex = input.indexOf("{{c", startIndex = index)
        if (openIndex == -1) break

        var cursor = openIndex + 3
        while (cursor < input.length && input[cursor].isDigit()) {
            cursor += 1
        }
        if (cursor == openIndex + 3 || !input.startsWith("::", startIndex = cursor)) {
            index = openIndex + 2
            continue
        }

        val ordinal = input.substring(openIndex + 3, cursor).toIntOrNull()
        if (ordinal == null) {
            index = openIndex + 2
            continue
        }

        val closeIndex = input.indexOf("}}", startIndex = cursor + 2)
        if (closeIndex == -1) break

        val body = input.substring(cursor + 2, closeIndex)
        val bodyParts = body.split("::", limit = 2)
        val text = bodyParts.firstOrNull().orEmpty().trim()
        val hint = bodyParts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (text.isNotEmpty()) {
            tokens += ClozeToken(ordinal = ordinal, text = text, hint = hint)
        }

        index = closeIndex + 2
    }

    return tokens
}

private fun parseTemplatePropSearchRulesFromJson(rawJson: String): List<TemplatePropSearchRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplatePropSearchRuleArray(JSONArray(trimmed))
            '{' -> parseTemplatePropSearchRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplatePropSearchConfigException("Invalid ankidroid-prop-search-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplatePropSearchRuleObject(jsonObject: JSONObject): List<TemplatePropSearchRule> =
    when {
        jsonObject.has("calls") -> parseTemplatePropSearchRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplatePropSearchRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplatePropSearchRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has("deck") || jsonObject.has("sourceField") -> listOf(parseTemplatePropSearchRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplatePropSearchRuleArray(jsonArray: JSONArray): List<TemplatePropSearchRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplatePropSearchRule(item))
        }
    }

private fun parseTemplatePropSearchRule(jsonObject: JSONObject): TemplatePropSearchRule {
    val deck = jsonObject.optString("deck").trim()
    val sourceField = jsonObject.optString("sourceField").trim()
    require(deck.isNotBlank()) { "ankidroid-prop-search-config requires a non-empty deck." }
    require(sourceField.isNotBlank()) { "ankidroid-prop-search-config requires a non-empty sourceField." }

    val insertCloze = jsonObject.optInt("insertCloze", 1)
    val searchCloze = jsonObject.optInt("searchCloze", 2)
    require(insertCloze >= 1) { "insertCloze must be 1 or greater." }
    require(searchCloze >= 1) { "searchCloze must be 1 or greater." }

    return TemplatePropSearchRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        deck = deck,
        sourceField = sourceField,
        insertCloze = insertCloze,
        searchCloze = searchCloze,
        matchMode = jsonObject.optString("matchMode", DEFAULT_MATCH_MODE).trim().ifBlank { DEFAULT_MATCH_MODE },
        applyMode = jsonObject.optString("applyMode", SEARCH_APPLY_MODE_REPLACE).trim().ifBlank { SEARCH_APPLY_MODE_REPLACE },
        tokenPattern = jsonObject.optString("tokenPattern", DEFAULT_TOKEN_PATTERN).trim().ifBlank { DEFAULT_TOKEN_PATTERN },
        maxResults = jsonObject.optInt("maxResults", DEFAULT_MAX_RESULTS).coerceAtLeast(2),
    )
}

const val SEARCH_MATCH_MODE_EXACT = "exact"
const val SEARCH_MATCH_MODE_PARTIAL = "partial"
const val SEARCH_APPLY_MODE_REPLACE = "replace"
const val SEARCH_APPLY_MODE_APPEND = "append"
const val SEARCH_APPLY_MODE_WHOLE_ENTRY = "wholeEntry"
private const val DEFAULT_MATCH_MODE = SEARCH_MATCH_MODE_EXACT
private const val DEFAULT_TOKEN_PATTERN = "[A-Za-z]+"
private const val DEFAULT_MAX_RESULTS = 8
