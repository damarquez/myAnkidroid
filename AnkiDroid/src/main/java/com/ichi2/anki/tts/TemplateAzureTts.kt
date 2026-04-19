package com.ichi2.anki.tts

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

private val TTS_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-tts-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

data class AzureSpeechSettings(
    val speechKey: String = "",
    val speechRegion: String = "",
    val voicesRaw: String = "zh-CN-XiaoxiaoNeural, zh-CN-YunxiNeural",
    val outputFormat: String = "audio-24khz-96kbitrate-mono-mp3",
) {
    val voices: List<String>
        get() =
            voicesRaw
                .split(',', '\n')
                .map(String::trim)
                .filter(String::isNotBlank)

    fun missingConfigurationMessage(context: Context): String? =
        when {
            speechKey.isBlank() -> context.getString(R.string.note_editor_tts_missing_speech_key)
            speechRegion.isBlank() -> context.getString(R.string.note_editor_tts_missing_speech_region)
            voices.isEmpty() -> context.getString(R.string.note_editor_tts_missing_voices)
            outputFormat.isBlank() -> context.getString(R.string.note_editor_tts_missing_output_format)
            else -> null
        }
}

data class TemplateAzureTtsRule(
    val field: String?,
    val sourceField: String?,
    val targetField: String?,
    val mode: String,
    val filenameField: String?,
    val filenamePrefixText: String?,
    val filenameSuffixText: String?,
    val maxFileNameLength: Int,
)

data class AzureTtsProcessResult(
    val transformedText: String,
    val storedFilenames: List<String>,
)

class TemplateAzureTtsConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class AzureSpeechPreferences(
    private val context: Context,
) {
    fun load(): AzureSpeechSettings {
        val prefs = context.sharedPrefs()
        return AzureSpeechSettings(
            speechKey = prefs.getString(context.getString(R.string.pref_azure_speech_key_key), "").orEmpty().trim(),
            speechRegion = prefs.getString(context.getString(R.string.pref_azure_speech_region_key), "").orEmpty().trim(),
            voicesRaw =
                prefs
                    .getString(
                        context.getString(R.string.pref_azure_speech_voices_key),
                        AzureSpeechSettings().voicesRaw,
                    ).orEmpty(),
            outputFormat =
                prefs
                    .getString(
                        context.getString(R.string.pref_azure_speech_output_format_key),
                        AzureSpeechSettings().outputFormat,
                    ).orEmpty()
                    .trim(),
        )
    }

    fun ensureDefaults() {
        val prefs = context.sharedPrefs()
        prefs.edit {
            val voicesKey = context.getString(R.string.pref_azure_speech_voices_key)
            val outputKey = context.getString(R.string.pref_azure_speech_output_format_key)
            if (!prefs.contains(voicesKey)) {
                putString(voicesKey, AzureSpeechSettings().voicesRaw)
            }
            if (!prefs.contains(outputKey)) {
                putString(outputKey, AzureSpeechSettings().outputFormat)
            }
        }
    }
}

class AzureSpeechSynthesizer(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun synthesize(
        text: String,
        voice: String,
        settings: AzureSpeechSettings,
    ): ByteArray =
        withContext(ioDispatcher) {
            require(settings.speechKey.isNotBlank()) { "Add the Azure speech key in Settings first." }
            require(settings.speechRegion.isNotBlank()) { "Add the Azure speech region in Settings first." }
            require(voice.isNotBlank()) { "Set at least one Azure voice in Settings first." }
            require(settings.outputFormat.isNotBlank()) { "Set the Azure output format in Settings first." }
            require(text.isNotBlank()) { "Azure TTS text cannot be blank." }

            val request =
                Request
                    .Builder()
                    .url("https://${settings.speechRegion.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
                    .header("Ocp-Apim-Subscription-Key", settings.speechKey.trim())
                    .header("Content-Type", "application/ssml+xml")
                    .header("X-Microsoft-OutputFormat", settings.outputFormat.trim())
                    .header("User-Agent", USER_AGENT)
                    .post(buildSsml(text = text, voice = voice).toRequestBody(SSML_MEDIA_TYPE))
                    .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyBytes = response.body.bytes()
                check(response.isSuccessful) {
                    val bodyPreview = bodyBytes.toString(Charsets.UTF_8).take(400)
                    "Azure speech request failed (${response.code}): $bodyPreview"
                }
                bodyBytes
            }
        }

    private fun buildSsml(
        text: String,
        voice: String,
    ): String {
        val locale = voice.substringBeforeLast('-', missingDelimiterValue = "zh-CN")
        return """
            <speak version="1.0" xml:lang="$locale">
                <voice name="$voice">${xmlEscape(text)}</voice>
            </speak>
            """.trimIndent()
    }

    private fun xmlEscape(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

    private companion object {
        const val USER_AGENT = "AnkiDroid"
        val SSML_MEDIA_TYPE = "application/ssml+xml; charset=utf-8".toMediaType()
    }
}

suspend fun generateAzureTtsForRule(
    rule: TemplateAzureTtsRule,
    sourceText: String,
    filenameBase: String,
    settings: AzureSpeechSettings,
    synthesizer: AzureSpeechSynthesizer,
    importAudio: suspend (desiredFilename: String, audioBytes: ByteArray, mimeType: String) -> String,
): AzureTtsProcessResult {
    val normalizedBase = truncateFilenameBase(sanitizeFilenameBase(filenameBase), rule.maxFileNameLength)
    check(normalizedBase.isNotBlank()) { "The configured filename base is empty after sanitizing." }

    return when {
        sourceText.isBlank() -> throw IllegalArgumentException("The source field is empty.")
        rule.mode == DEFAULT_TTS_MODE -> processNumberedMandarinExamples(sourceText, normalizedBase, settings, synthesizer, importAudio)
        rule.mode == SIMPLE_EXAMPLE_TTS_MODE -> processSimpleExample(sourceText, normalizedBase, settings, synthesizer, importAudio)
        else -> throw IllegalArgumentException("Unsupported TTS mode '${rule.mode}'.")
    }
}

fun parseTemplateAzureTtsRules(noteType: NotetypeJson): List<TemplateAzureTtsRule> {
    val rules = mutableListOf<TemplateAzureTtsRule>()
    for (template in noteType.templates) {
        rules += parseTemplateAzureTtsRules(template.qfmt)
        rules += parseTemplateAzureTtsRules(template.afmt)
    }
    return rules
}

fun parseTemplateAzureTtsRules(templateHtml: String): List<TemplateAzureTtsRule> =
    TTS_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .flatMap { parseTemplateAzureTtsRulesFromJson(it.groupValues[1]).asSequence() }
        .toList()

fun selectTemplateAzureTtsRule(
    rules: List<TemplateAzureTtsRule>,
    currentFieldName: String,
): TemplateAzureTtsRule? = rules.firstOrNull { it.field == currentFieldName } ?: rules.firstOrNull { it.field == null }

fun buildTemplateAzureTtsFilenameBase(
    rule: TemplateAzureTtsRule,
    currentFieldValues: Map<String, String>,
): String {
    val fieldValue =
        if (!rule.filenameField.isNullOrBlank()) {
            currentFieldValues[rule.filenameField]
                ?: throw TemplateAzureTtsConfigException("Field '${rule.filenameField}' referenced in ankidroid-tts-config was not found.")
        } else {
            ""
        }

    val rawBase = "${rule.filenamePrefixText.orEmpty()}$fieldValue${rule.filenameSuffixText.orEmpty()}"
    return truncateFilenameBase(sanitizeFilenameBase(rawBase), rule.maxFileNameLength)
}

private suspend fun processNumberedMandarinExamples(
    sourceText: String,
    filenameBase: String,
    settings: AzureSpeechSettings,
    synthesizer: AzureSpeechSynthesizer,
    importAudio: suspend (desiredFilename: String, audioBytes: ByteArray, mimeType: String) -> String,
): AzureTtsProcessResult {
    val normalizedExamples = NumberedMandarinExamplesFormatter.normalizeExamplesText(sourceText)
    val parsedExamples = NumberedMandarinExamplesFormatter.parseExamples(normalizedExamples)
    check(parsedExamples.blocks.isNotEmpty()) { "No numbered Mandarin example lines were found." }

    val audioInfo = audioInfoFor(settings.outputFormat)
    val storedFilesByBlock = linkedMapOf<String, List<String>>()
    val storedFilenames = mutableListOf<String>()

    for (block in parsedExamples.blocks) {
        val storedForBlock =
            settings.voices.mapIndexed { voiceIndex, voice ->
                val desiredFilename =
                    desiredExampleFilename(filenameBase, block.number, voiceIndex, settings.voices.size, audioInfo.extension)
                val audioBytes =
                    synthesizer.synthesize(
                        text = block.chineseText,
                        voice = voice,
                        settings = settings,
                    )
                importAudio(desiredFilename, audioBytes, audioInfo.mimeType).also { storedFilenames += it }
            }
        storedFilesByBlock[block.number] = storedForBlock
    }

    return AzureTtsProcessResult(
        transformedText = NumberedMandarinExamplesFormatter.buildTransformedExamples(parsedExamples, storedFilesByBlock),
        storedFilenames = storedFilenames,
    )
}

private suspend fun processSimpleExample(
    sourceText: String,
    filenameBase: String,
    settings: AzureSpeechSettings,
    synthesizer: AzureSpeechSynthesizer,
    importAudio: suspend (desiredFilename: String, audioBytes: ByteArray, mimeType: String) -> String,
): AzureTtsProcessResult {
    val audioInfo = audioInfoFor(settings.outputFormat)
    val storedFilenames =
        settings.voices.mapIndexed { voiceIndex, voice ->
            val desiredFilename = "$filenameBase${voiceLabel(voiceIndex)}.${audioInfo.extension}"
            val audioBytes =
                synthesizer.synthesize(
                    text = sourceText,
                    voice = voice,
                    settings = settings,
                )
            importAudio(desiredFilename, audioBytes, audioInfo.mimeType)
        }
    return AzureTtsProcessResult(
        transformedText = storedFilenames.joinToString(separator = "") { "[sound:$it]" },
        storedFilenames = storedFilenames,
    )
}

private fun desiredExampleFilename(
    filenameBase: String,
    blockNumber: String,
    voiceIndex: Int,
    voiceCount: Int,
    extension: String,
): String =
    if (voiceCount <= 1) {
        "$filenameBase$blockNumber.$extension"
    } else {
        "$filenameBase$blockNumber-${voiceLabel(voiceIndex)}.$extension"
    }

private fun voiceLabel(voiceIndex: Int): String {
    val zeroBased = voiceIndex.coerceAtLeast(0)
    return if (zeroBased < 26) {
        ('a'.code + zeroBased).toChar().toString()
    } else {
        (zeroBased + 1).toString()
    }
}

private fun audioInfoFor(outputFormat: String): AudioInfo {
    val lowered = outputFormat.lowercase()
    return when {
        "mp3" in lowered -> AudioInfo(extension = "mp3", mimeType = "audio/mpeg")
        "opus" in lowered || "ogg" in lowered -> AudioInfo(extension = "ogg", mimeType = "audio/ogg")
        "wav" in lowered || "riff" in lowered || "pcm" in lowered -> AudioInfo(extension = "wav", mimeType = "audio/wav")
        else -> AudioInfo(extension = "bin", mimeType = "application/octet-stream")
    }
}

private fun sanitizeFilenameBase(name: String): String =
    INVALID_FILENAME_CHARS
        .replace(name, "")
        .replace(CONSECUTIVE_WHITESPACE, " ")
        .trim()

private fun truncateFilenameBase(
    base: String,
    maxFileNameLength: Int,
): String = base.take(maxFileNameLength).trim()

private fun parseTemplateAzureTtsRulesFromJson(rawJson: String): List<TemplateAzureTtsRule> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    return try {
        when (trimmed.first()) {
            '[' -> parseTemplateAzureTtsRuleArray(JSONArray(trimmed))
            '{' -> parseTemplateAzureTtsRuleObject(JSONObject(trimmed))
            else -> emptyList()
        }
    } catch (e: Exception) {
        throw TemplateAzureTtsConfigException("Invalid ankidroid-tts-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseTemplateAzureTtsRuleObject(jsonObject: JSONObject): List<TemplateAzureTtsRule> =
    when {
        jsonObject.has("calls") -> parseTemplateAzureTtsRuleArray(jsonObject.optJSONArray("calls") ?: JSONArray())
        jsonObject.has("entries") -> parseTemplateAzureTtsRuleArray(jsonObject.optJSONArray("entries") ?: JSONArray())
        jsonObject.has("rules") -> parseTemplateAzureTtsRuleArray(jsonObject.optJSONArray("rules") ?: JSONArray())
        jsonObject.has(
            "mode",
        ) || jsonObject.has("sourceField") || jsonObject.has("targetField") -> listOf(parseTemplateAzureTtsRule(jsonObject))
        else -> emptyList()
    }

private fun parseTemplateAzureTtsRuleArray(jsonArray: JSONArray): List<TemplateAzureTtsRule> =
    buildList {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            add(parseTemplateAzureTtsRule(item))
        }
    }

private fun parseTemplateAzureTtsRule(jsonObject: JSONObject): TemplateAzureTtsRule {
    val mode = jsonObject.optString("mode", DEFAULT_TTS_MODE).trim().ifBlank { DEFAULT_TTS_MODE }
    require(mode == DEFAULT_TTS_MODE || mode == SIMPLE_EXAMPLE_TTS_MODE) {
        "Unsupported ankidroid-tts-config mode '$mode'."
    }
    return TemplateAzureTtsRule(
        field = jsonObject.optString("field").trim().ifBlank { null },
        sourceField = jsonObject.optString("sourceField").trim().ifBlank { null },
        targetField = jsonObject.optString("targetField").trim().ifBlank { null },
        mode = mode,
        filenameField = jsonObject.optString("filenameField").trim().ifBlank { null },
        filenamePrefixText = jsonObject.optString("filenamePrefixText").ifBlank { null },
        filenameSuffixText = jsonObject.optString("filenameSuffixText").ifBlank { null },
        maxFileNameLength = jsonObject.optInt("maxFileNameLength", DEFAULT_MAX_FILENAME_LENGTH).coerceAtLeast(1),
    )
}

private object NumberedMandarinExamplesFormatter {
    fun normalizeExamplesText(value: String): String {
        val normalizedNewlines = value.replace("\r\n", "\n").replace('\r', '\n')
        val trimmed = normalizedNewlines.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return normalizedNewlines.trim()
        }
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline == -1) {
            return normalizedNewlines.trim()
        }
        return trimmed.substring(firstNewline + 1, trimmed.length - 3).trim()
    }

    fun parseExamples(value: String): ParsedExamples {
        val cleanLines = value.split('\n').map(::stripExistingMarkup)
        val blocks = mutableListOf<ExampleBlock>()
        var index = 0
        while (index < cleanLines.size) {
            val trimmed = cleanLines[index].trim()

            val combinedMatch = NUMBERED_CHINESE_LINE.find(trimmed)
            if (combinedMatch != null) {
                val number = combinedMatch.groupValues[1]
                val chineseText = combinedMatch.groupValues[2].trim()
                val (pinyinIdx, translationIdx) = findCompanionLines(cleanLines, index + 1)
                blocks +=
                    ExampleBlock(
                        numberLineIndex = index,
                        number = number,
                        chineseText = chineseText,
                        chineseLineIndex = index,
                        pinyinLineIndex = pinyinIdx,
                        translationLineIndex = translationIdx,
                    )
                index++
                continue
            }

            val numberOnlyMatch = NUMBER_ONLY_LINE.find(trimmed)
            if (numberOnlyMatch != null) {
                val number = numberOnlyMatch.groupValues[1]
                val nextIdx = index + 1
                if (nextIdx < cleanLines.size) {
                    val nextLine = cleanLines[nextIdx].trim()
                    if (nextLine.isNotBlank() && STARTS_WITH_CJK.containsMatchIn(nextLine)) {
                        val (pinyinIdx, translationIdx) = findCompanionLines(cleanLines, nextIdx + 1)
                        blocks +=
                            ExampleBlock(
                                numberLineIndex = index,
                                number = number,
                                chineseText = nextLine,
                                chineseLineIndex = nextIdx,
                                pinyinLineIndex = pinyinIdx,
                                translationLineIndex = translationIdx,
                            )
                    }
                }
            }

            index++
        }
        return ParsedExamples(lines = cleanLines, blocks = blocks)
    }

    fun buildTransformedExamples(
        parsedExamples: ParsedExamples,
        storedFilesByBlock: Map<String, List<String>>,
    ): String {
        val pinyinIndices = parsedExamples.blocks.mapNotNull { it.pinyinLineIndex }.toSet()
        val translationIndices = parsedExamples.blocks.mapNotNull { it.translationLineIndex }.toSet()
        val chineseOnlyIndices =
            parsedExamples.blocks
                .filter { it.chineseLineIndex != it.numberLineIndex }
                .map { it.chineseLineIndex }
                .toSet()
        val blockByNumberLine = parsedExamples.blocks.associateBy { it.numberLineIndex }

        val output = mutableListOf<String>()
        for ((index, line) in parsedExamples.lines.withIndex()) {
            val block = blockByNumberLine[index]
            when {
                block != null -> {
                    val audioMarkup =
                        storedFilesByBlock[block.number].orEmpty().joinToString(
                            separator = "",
                        ) { filename -> "[sound:$filename]" }
                    output += "${block.number}. $audioMarkup".trimEnd()
                    if (block.chineseLineIndex == block.numberLineIndex) {
                        output += appendHiddenSuffix(block.chineseText, 1)
                    }
                }
                index in chineseOnlyIndices -> output += appendHiddenSuffix(line, 1)
                index in pinyinIndices -> output += appendHiddenSuffix(line, 2)
                index in translationIndices -> output += appendHiddenSuffix(line, 3)
                else -> output += line
            }
        }
        return output.joinToString("\n").trim()
    }

    private fun findCompanionLines(
        lines: List<String>,
        startIndex: Int,
    ): Pair<Int?, Int?> {
        var pinyinIdx: Int? = null
        var translationIdx: Int? = null
        for (j in startIndex until minOf(startIndex + 3, lines.size)) {
            val line = lines[j].trim()
            if (line.isBlank()) break
            if (NUMBERED_CHINESE_LINE.containsMatchIn(line)) break
            if (NUMBER_ONLY_LINE.containsMatchIn(line)) break
            if (pinyinIdx == null && line.startsWith("(") && !line.startsWith("(*")) {
                pinyinIdx = j
            } else if (translationIdx == null && (line.startsWith("\"") || line.startsWith("\u201c"))) {
                translationIdx = j
            }
        }
        return pinyinIdx to translationIdx
    }

    private fun stripExistingMarkup(value: String): String =
        value
            .replace(SOUND_TAG, "")
            .replace(DOUBLED_SOUND_TAG, "")
            .replace(BRACE_AUDIO_TOKEN, "")
            .replace(HIDDEN_LINE_SUFFIX, "")
            .replace(SPACES_BEFORE_NEWLINE, "\n")
            .trimEnd()

    private fun appendHiddenSuffix(
        line: String,
        marker: Int,
    ): String {
        val stripped = HIDDEN_LINE_SUFFIX.replace(line, "").trimEnd()
        return "$stripped(*$marker)"
    }

    data class ParsedExamples(
        val lines: List<String>,
        val blocks: List<ExampleBlock>,
    )

    data class ExampleBlock(
        val numberLineIndex: Int,
        val number: String,
        val chineseText: String,
        val chineseLineIndex: Int,
        val pinyinLineIndex: Int?,
        val translationLineIndex: Int?,
    )

    private val NUMBERED_CHINESE_LINE = Regex("""^(\d+(?:\.\d+)*)\.\s*([\p{IsHan}].*)$""")
    private val NUMBER_ONLY_LINE = Regex("""^(\d+(?:\.\d+)*)\.\s*$""")
    private val STARTS_WITH_CJK = Regex("""^[\p{IsHan}]""")
    private val HIDDEN_LINE_SUFFIX = Regex("""\s*\(\*\d?\)\s*$""")
    private val SOUND_TAG = Regex("""\[sound:[^\]\r\n]+\]""")
    private val DOUBLED_SOUND_TAG = Regex("""\{\*\[sound:[^\]\r\n]+\]\*\}""")
    private val BRACE_AUDIO_TOKEN = Regex("""\{[^}\r\n]+\.(?:mp3|wav|ogg|m4a)\}""", RegexOption.IGNORE_CASE)
    private val SPACES_BEFORE_NEWLINE = Regex("""[ \t]+\n""")
}

private data class AudioInfo(
    val extension: String,
    val mimeType: String,
)

private const val DEFAULT_TTS_MODE = "numberedMandarinExamples"
private const val SIMPLE_EXAMPLE_TTS_MODE = "simpleExample"
private const val DEFAULT_MAX_FILENAME_LENGTH = 15
private val INVALID_FILENAME_CHARS = Regex("""[/\\<>:"|?*]""")
private val CONSECUTIVE_WHITESPACE = Regex("""\s+""")
