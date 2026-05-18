package com.ichi2.anki.props

import com.ichi2.anki.libanki.NotetypeJson
import org.json.JSONArray
import org.json.JSONObject

private val SET_SEARCH_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-set-search-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class TemplateSetSearchRule(
    val field: String?,
    val deck: String,
    val searchField: String,
    val labelField: String,
    val matchMode: String,
    val applyMode: String,
    val tokenPattern: String,
    val maxResults: Int,
    val insertAsLink: Boolean,
)

class TemplateSetSearchConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun parseTemplateSetSearchRules(noteType: NotetypeJson): List<TemplateSetSearchRule> {
    val rules = mutableListOf<TemplateSetSearchRule>()
    for (template in noteType.templates) {
        rules += parseTemplateSetSearchRules(template.qfmt)
        rules += parseTemplateSetSearchRules(template.afmt)
    }
    return rules
}

fun parseTemplateSetSearchRules(templateHtml: String): List<TemplateSetSearchRule> =
    SET_SEARCH_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplateSetSearchRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

fun selectTemplateSetSearchRule(
    rules: List<TemplateSetSearchRule>,
    currentFieldName: String,
): TemplateSetSearchRule? = rules.firstOrNull { it.field == currentFieldName } ?: rules.firstOrNull { it.field == null }

private fun parseTemplateSetSearchRulesFromJson(rawJson: String): List<TemplateSetSearchRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) return emptyList()
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplateSetSearchRuleArray(JSONArray(trimmed))
            '{' -> parseTemplateSetSearchRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplateSetSearchConfigException("Invalid ankidroid-set-search-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplateSetSearchRuleObject(jsonObject: JSONObject): List<TemplateSetSearchRule> =
    when {
        jsonObject.has("calls") -> parseTemplateSetSearchRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplateSetSearchRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplateSetSearchRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has("deck") || jsonObject.has("searchField") -> listOf(parseTemplateSetSearchRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplateSetSearchRuleArray(jsonArray: JSONArray): List<TemplateSetSearchRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplateSetSearchRule(item))
        }
    }

private fun parseTemplateSetSearchRule(jsonObject: JSONObject): TemplateSetSearchRule {
    val deck = jsonObject.optString("deck").trim()
    val searchField = jsonObject.optString("searchField").trim()
    val labelField = jsonObject.optString("labelField").trim()
    require(deck.isNotBlank()) { "ankidroid-set-search-config requires a non-empty deck." }
    require(searchField.isNotBlank()) { "ankidroid-set-search-config requires a non-empty searchField." }
    require(labelField.isNotBlank()) { "ankidroid-set-search-config requires a non-empty labelField." }
    return TemplateSetSearchRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        deck = deck,
        searchField = searchField,
        labelField = labelField,
        matchMode = jsonObject.optString("matchMode", SEARCH_MATCH_MODE_EXACT).trim().ifBlank { SEARCH_MATCH_MODE_EXACT },
        applyMode = jsonObject.optString("applyMode", SEARCH_APPLY_MODE_APPEND).trim().ifBlank { SEARCH_APPLY_MODE_APPEND },
        tokenPattern = jsonObject.optString("tokenPattern", DEFAULT_SET_TOKEN_PATTERN).trim().ifBlank { DEFAULT_SET_TOKEN_PATTERN },
        maxResults = jsonObject.optInt("maxResults", DEFAULT_SET_MAX_RESULTS).coerceAtLeast(2),
        insertAsLink = jsonObject.optBoolean("insertAsLink", false),
    )
}

private const val DEFAULT_SET_TOKEN_PATTERN = "-?[\\p{L}\\p{M}]+"
private const val DEFAULT_SET_MAX_RESULTS = 8
