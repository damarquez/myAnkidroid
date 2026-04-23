package com.ichi2.anki.ranking

import com.ichi2.anki.libanki.NotetypeJson
import org.json.JSONArray
import org.json.JSONObject

private val FREQUENCY_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-frequency-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class TemplateFrequencyLookupRule(
    val field: String?,
    val deck: String?,
    val sourceField: String,
    val targetField: String?,
    val format: String,
    val rankType: String,
    val removeNonAlphabeticChars: Boolean,
)

class TemplateFrequencyLookupConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun parseTemplateFrequencyLookupRules(noteType: NotetypeJson): List<TemplateFrequencyLookupRule> {
    val rules = mutableListOf<TemplateFrequencyLookupRule>()
    for (template in noteType.templates) {
        rules += parseTemplateFrequencyLookupRules(template.qfmt)
        rules += parseTemplateFrequencyLookupRules(template.afmt)
    }
    return rules
}

fun parseTemplateFrequencyLookupRules(templateHtml: String): List<TemplateFrequencyLookupRule> =
    FREQUENCY_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplateFrequencyLookupRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

fun selectTemplateFrequencyLookupRule(
    rules: List<TemplateFrequencyLookupRule>,
    currentFieldName: String,
    currentDeckName: String?,
): TemplateFrequencyLookupRule? {
    val exactFieldRules = rules.filter { it.field == currentFieldName }
    selectTemplateFrequencyLookupRuleForDeck(exactFieldRules, currentDeckName)?.let { return it }

    val genericFieldRules = rules.filter { it.field == null }
    return selectTemplateFrequencyLookupRuleForDeck(genericFieldRules, currentDeckName)
}

fun hasTemplateFrequencyLookupRuleForField(
    rules: List<TemplateFrequencyLookupRule>,
    currentFieldName: String,
): Boolean = rules.any { it.field == currentFieldName || it.field == null }

private fun selectTemplateFrequencyLookupRuleForDeck(
    rules: List<TemplateFrequencyLookupRule>,
    currentDeckName: String?,
): TemplateFrequencyLookupRule? =
    rules.firstOrNull { rule ->
        rule.deck == null || (currentDeckName != null && rule.deck == currentDeckName)
    }

private fun parseTemplateFrequencyLookupRulesFromJson(rawJson: String): List<TemplateFrequencyLookupRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) return emptyList()
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplateFrequencyLookupRuleArray(JSONArray(trimmed))
            '{' -> parseTemplateFrequencyLookupRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplateFrequencyLookupConfigException("Invalid ankidroid-frequency-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplateFrequencyLookupRuleObject(jsonObject: JSONObject): List<TemplateFrequencyLookupRule> =
    when {
        jsonObject.has("calls") -> parseTemplateFrequencyLookupRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplateFrequencyLookupRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplateFrequencyLookupRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has("sourceField") -> listOf(parseTemplateFrequencyLookupRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplateFrequencyLookupRuleArray(jsonArray: JSONArray): List<TemplateFrequencyLookupRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplateFrequencyLookupRule(item))
        }
    }

private fun parseTemplateFrequencyLookupRule(jsonObject: JSONObject): TemplateFrequencyLookupRule {
    val sourceField = jsonObject.optString("sourceField").trim()
    require(sourceField.isNotBlank()) { "ankidroid-frequency-config requires a non-empty sourceField." }
    val format = jsonObject.optString("format", FREQUENCY_FORMAT_RANK_AND_TERM).trim().ifBlank { FREQUENCY_FORMAT_RANK_AND_TERM }
    require(format == FREQUENCY_FORMAT_RANK_ONLY || format == FREQUENCY_FORMAT_RANK_AND_TERM) {
        "Unsupported ankidroid-frequency-config format '$format'."
    }
    val rankType = jsonObject.optString("rankType", FREQUENCY_RANK_TYPE_AUTO).trim().ifBlank { FREQUENCY_RANK_TYPE_AUTO }
    require(rankType == FREQUENCY_RANK_TYPE_AUTO || rankType == FREQUENCY_RANK_TYPE_CHAR || rankType == FREQUENCY_RANK_TYPE_GLOBAL) {
        "Unsupported ankidroid-frequency-config rankType '$rankType'."
    }
    return TemplateFrequencyLookupRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        deck = jsonObject.optString("deck").trim().ifBlank { null },
        sourceField = sourceField,
        targetField = jsonObject.optString("targetField").trim().ifBlank { null },
        format = format,
        rankType = rankType,
        removeNonAlphabeticChars =
            jsonObject.optBoolean("removeNonAlphabeticChars") ||
                jsonObject.optBoolean("removeNonAlphabeticalChars"),
    )
}

const val FREQUENCY_FORMAT_RANK_ONLY = "rankOnly"
const val FREQUENCY_FORMAT_RANK_AND_TERM = "rankAndTerm"
const val FREQUENCY_RANK_TYPE_AUTO = "auto"
const val FREQUENCY_RANK_TYPE_CHAR = "char"
const val FREQUENCY_RANK_TYPE_GLOBAL = "global"
