package com.ichi2.anki.ai

import android.content.Context
import androidx.core.content.edit
import com.ichi2.anki.R
import com.ichi2.anki.ioDispatcher
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.preferences.sharedPrefs
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val AI_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-ai-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class TemplateAiPromptRule(
    val field: String?,
    val prompt: String,
    val prefixText: String?,
    val prefixField: String?,
    val suffixText: String?,
    val suffixField: String?,
)

enum class AiProvider(
    val preferenceValue: String,
) {
    OpenAI("openai"),
    Anthropic("anthropic"),
    OpenRouter("openrouter"),
    ;

    companion object {
        fun fromPreferenceValue(value: String?): AiProvider = entries.firstOrNull { it.preferenceValue == value } ?: OpenAI
    }
}

data class AiServiceSettings(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
) {
    fun missingConfigurationMessage(context: Context): String? =
        when {
            apiKey.isBlank() -> context.getString(R.string.note_editor_ai_generate_missing_api_key)
            model.isBlank() -> context.getString(R.string.note_editor_ai_generate_missing_model)
            else -> null
        }
}

class TemplateAiConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class NoteEditorAiPreferences(
    private val context: Context,
) {
    fun load(): AiServiceSettings {
        val prefs = context.sharedPrefs()
        return AiServiceSettings(
            provider =
                AiProvider.fromPreferenceValue(
                    prefs.getString(context.getString(R.string.pref_ai_provider_key), AiProvider.OpenAI.preferenceValue),
                ),
            apiKey = prefs.getString(context.getString(R.string.pref_ai_api_key_key), "").orEmpty().trim(),
            model = prefs.getString(context.getString(R.string.pref_ai_model_key), "").orEmpty().trim(),
        )
    }

    fun ensureDefaults() {
        val prefs = context.sharedPrefs()
        val providerKey = context.getString(R.string.pref_ai_provider_key)
        prefs.edit {
            if (!prefs.contains(providerKey)) {
                putString(providerKey, AiProvider.OpenAI.preferenceValue)
            }
        }
    }
}

class NoteEditorAiTextGenerator(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun generateText(
        settings: AiServiceSettings,
        promptText: String,
    ): String =
        withContext(ioDispatcher) {
            require(promptText.isNotBlank()) { "Prompt text is empty." }
            when (settings.provider) {
                AiProvider.OpenAI -> callOpenAi(settings, promptText)
                AiProvider.Anthropic -> callAnthropic(settings, promptText)
                AiProvider.OpenRouter -> callOpenRouter(settings, promptText)
            }
        }

    private fun callOpenAi(
        settings: AiServiceSettings,
        promptText: String,
    ): String {
        val payload =
            JSONObject()
                .put("model", settings.model)
                .put("input", promptText)

        val request =
            Request
                .Builder()
                .url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            check(response.isSuccessful) {
                "OpenAI request failed (${response.code}): ${responseBody.take(400)}"
            }

            val json = JSONObject(responseBody)
            val outputText = json.optString("output_text")
            if (outputText.isNotBlank()) {
                return outputText.trim()
            }

            val output = json.optJSONArray("output") ?: JSONArray()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text") {
                        val text = part.optString("text")
                        if (text.isNotBlank()) {
                            return text.trim()
                        }
                    }
                }
            }

            error("OpenAI response did not contain text output.")
        }
    }

    private fun callAnthropic(
        settings: AiServiceSettings,
        promptText: String,
    ): String {
        val payload =
            JSONObject()
                .put("model", settings.model)
                .put("max_tokens", 1200)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", promptText),
                    ),
                )

        val request =
            Request
                .Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", settings.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            check(response.isSuccessful) {
                "Anthropic request failed (${response.code}): ${responseBody.take(400)}"
            }

            val json = JSONObject(responseBody)
            val content = json.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    val text = part.optString("text")
                    if (text.isNotBlank()) {
                        return text.trim()
                    }
                }
            }

            error("Anthropic response did not contain text output.")
        }
    }

    private fun callOpenRouter(
        settings: AiServiceSettings,
        promptText: String,
    ): String {
        val payload =
            JSONObject()
                .put("model", settings.model)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", promptText),
                    ),
                )

        val request =
            Request
                .Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .header("X-Title", "AnkiDroid")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            check(response.isSuccessful) {
                "OpenRouter request failed (${response.code}): ${responseBody.take(400)}"
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: JSONArray()
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                val message = choice.optJSONObject("message") ?: continue
                when (val contentValue = message.opt("content")) {
                    is String -> if (contentValue.isNotBlank()) return contentValue.trim()
                    is JSONArray -> {
                        for (j in 0 until contentValue.length()) {
                            val part = contentValue.optJSONObject(j) ?: continue
                            if (part.optString("type") == "text") {
                                val text = part.optString("text")
                                if (text.isNotBlank()) {
                                    return text.trim()
                                }
                            }
                        }
                    }
                }
            }

            error("OpenRouter response did not contain text output.")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

fun parseTemplateAiPromptRules(noteType: NotetypeJson): List<TemplateAiPromptRule> {
    val rules = mutableListOf<TemplateAiPromptRule>()
    for (template in noteType.templates) {
        rules += parseTemplateAiPromptRules(template.qfmt)
        rules += parseTemplateAiPromptRules(template.afmt)
    }
    return rules
}

fun parseTemplateAiPromptRules(templateHtml: String): List<TemplateAiPromptRule> =
    AI_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplateAiPromptRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

private fun parseTemplateAiPromptRulesFromJson(rawJson: String): List<TemplateAiPromptRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplateAiPromptRuleArray(JSONArray(trimmed))
            '{' -> parseTemplateAiPromptRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplateAiConfigException("Invalid ankidroid-ai-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplateAiPromptRuleObject(jsonObject: JSONObject): List<TemplateAiPromptRule> =
    when {
        jsonObject.has("calls") -> parseTemplateAiPromptRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplateAiPromptRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplateAiPromptRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has("prompt") -> listOf(parseTemplateAiPromptRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplateAiPromptRuleArray(jsonArray: JSONArray): List<TemplateAiPromptRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplateAiPromptRule(item))
        }
    }

private fun parseTemplateAiPromptRule(jsonObject: JSONObject): TemplateAiPromptRule {
    val prompt = jsonObject.optString("prompt").trim()
    if (prompt.isBlank()) {
        throw TemplateAiConfigException("Each ankidroid-ai-config entry must define a non-empty prompt.")
    }
    return TemplateAiPromptRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        prompt = prompt,
        prefixText = jsonObject.optString("prefixText").ifBlank { null },
        prefixField = jsonObject.optString("prefixField").trim().ifBlank { null },
        suffixText = jsonObject.optString("suffixText").ifBlank { null },
        suffixField = jsonObject.optString("suffixField").trim().ifBlank { null },
    )
}

fun selectTemplateAiPromptRule(
    rules: List<TemplateAiPromptRule>,
    currentFieldName: String,
): TemplateAiPromptRule? = rules.firstOrNull { it.field == currentFieldName } ?: rules.firstOrNull { it.field == null }

fun buildTemplateAiPrompt(
    rule: TemplateAiPromptRule,
    currentFieldValues: Map<String, String>,
): String =
    buildString {
        append(resolveTemplateAiSegment(rule.prefixText, rule.prefixField, currentFieldValues))
        append(rule.prompt)
        append(resolveTemplateAiSegment(rule.suffixText, rule.suffixField, currentFieldValues))
    }

private fun resolveTemplateAiSegment(
    text: String?,
    fieldName: String?,
    currentFieldValues: Map<String, String>,
): String =
    buildString {
        if (!text.isNullOrEmpty()) {
            append(text)
        }
        if (!fieldName.isNullOrBlank()) {
            val fieldValue =
                currentFieldValues[fieldName]
                    ?: throw TemplateAiConfigException("Field '$fieldName' referenced in ankidroid-ai-config was not found.")
            append(fieldValue)
        }
    }
