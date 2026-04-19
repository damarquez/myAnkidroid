package com.ichi2.anki.props

import com.ichi2.anki.libanki.NotetypeJson
import org.json.JSONArray
import org.json.JSONObject

private val ACTOR_SEARCH_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-actor-search-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class TemplateActorSearchRule(
    val field: String?,
    val deck: String,
    val sourceField: String,
    val searchCloze: Int,
    val labelCloze: Int,
    val matchMode: String,
    val applyMode: String,
    val tokenPattern: String,
    val maxResults: Int,
)

class TemplateActorSearchConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun parseTemplateActorSearchRules(noteType: NotetypeJson): List<TemplateActorSearchRule> {
    val rules = mutableListOf<TemplateActorSearchRule>()
    for (template in noteType.templates) {
        rules += parseTemplateActorSearchRules(template.qfmt)
        rules += parseTemplateActorSearchRules(template.afmt)
    }
    return rules
}

fun parseTemplateActorSearchRules(templateHtml: String): List<TemplateActorSearchRule> =
    ACTOR_SEARCH_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplateActorSearchRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

fun selectTemplateActorSearchRule(
    rules: List<TemplateActorSearchRule>,
    currentFieldName: String,
): TemplateActorSearchRule? = rules.firstOrNull { it.field == currentFieldName } ?: rules.firstOrNull { it.field == null }

private fun parseTemplateActorSearchRulesFromJson(rawJson: String): List<TemplateActorSearchRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) return emptyList()
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplateActorSearchRuleArray(JSONArray(trimmed))
            '{' -> parseTemplateActorSearchRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplateActorSearchConfigException("Invalid ankidroid-actor-search-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplateActorSearchRuleObject(jsonObject: JSONObject): List<TemplateActorSearchRule> =
    when {
        jsonObject.has("calls") -> parseTemplateActorSearchRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplateActorSearchRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplateActorSearchRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has("deck") || jsonObject.has("sourceField") -> listOf(parseTemplateActorSearchRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplateActorSearchRuleArray(jsonArray: JSONArray): List<TemplateActorSearchRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplateActorSearchRule(item))
        }
    }

private fun parseTemplateActorSearchRule(jsonObject: JSONObject): TemplateActorSearchRule {
    val deck = jsonObject.optString("deck").trim()
    val sourceField = jsonObject.optString("sourceField").trim()
    require(deck.isNotBlank()) { "ankidroid-actor-search-config requires a non-empty deck." }
    require(sourceField.isNotBlank()) { "ankidroid-actor-search-config requires a non-empty sourceField." }
    val searchCloze = jsonObject.optInt("searchCloze", 1)
    val labelCloze = jsonObject.optInt("labelCloze", 2)
    require(searchCloze >= 1) { "searchCloze must be 1 or greater." }
    require(labelCloze >= 1) { "labelCloze must be 1 or greater." }
    return TemplateActorSearchRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        deck = deck,
        sourceField = sourceField,
        searchCloze = searchCloze,
        labelCloze = labelCloze,
        matchMode = jsonObject.optString("matchMode", SEARCH_MATCH_MODE_EXACT).trim().ifBlank { SEARCH_MATCH_MODE_EXACT },
        applyMode = jsonObject.optString("applyMode", SEARCH_APPLY_MODE_APPEND).trim().ifBlank { SEARCH_APPLY_MODE_APPEND },
        tokenPattern = jsonObject.optString("tokenPattern", DEFAULT_ACTOR_TOKEN_PATTERN).trim().ifBlank { DEFAULT_ACTOR_TOKEN_PATTERN },
        maxResults = jsonObject.optInt("maxResults", DEFAULT_ACTOR_MAX_RESULTS).coerceAtLeast(2),
    )
}

private const val DEFAULT_ACTOR_TOKEN_PATTERN = "[A-Za-z]+-?"
private const val DEFAULT_ACTOR_MAX_RESULTS = 8
