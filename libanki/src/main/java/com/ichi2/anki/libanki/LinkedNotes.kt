package com.ichi2.anki.libanki

import anki.search.SearchNodeKt.field
import anki.search.searchNode
import org.json.JSONObject
import java.nio.charset.StandardCharsets

private val LINKED_NOTE_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-linked-note-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private const val LINKED_NOTE_GUID_HEX_PREFIX = "guidhex:"
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

enum class LinkedNoteDisplayMode {
    MERGED,
    ORIGINAL,
}

data class LinkedNoteConfig(
    val linkedNoteField: String,
    val deck: String?,
    val searchField: String?,
    val labelFields: List<String>,
    val maxResults: Int,
    val implicitLinks: List<ImplicitLinkRule> = emptyList(),
)

/**
 * Links a note without a stored GUID: when [sourceField] of a note in [fromDeck] matches [pattern],
 * the note links to the only note in [toDeck] whose [toField] (or [fallbackField]) equals the
 * pattern's first group. E.g. Vocabulary "好." links to Characters "好".
 */
data class ImplicitLinkRule(
    val fromDeck: String?,
    val sourceField: String?,
    val pattern: Regex,
    val toDeck: String,
    val toField: String,
    val fallbackField: String?,
)

private val LINKED_NOTE_GUID_STORAGE_REGEX = Regex("""\{([^{}\r\n]+)\}""")

data class LinkedNoteRelation(
    val config: LinkedNoteConfig,
    val linkedNoteGuid: String,
    val linkedNote: Note,
    /** Set when the link comes from an [ImplicitLinkRule]: the field holding the looked-up text */
    val implicitSourceField: String? = null,
) {
    val isImplicit get() = implicitSourceField != null
}

class LinkedNoteConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun parseTemplateLinkedNoteConfig(noteType: NotetypeJson): LinkedNoteConfig? {
    for (template in noteType.templates) {
        parseTemplateLinkedNoteConfig(template.qfmt)?.let { return it }
        parseTemplateLinkedNoteConfig(template.afmt)?.let { return it }
    }
    return null
}

fun parseTemplateLinkedNoteConfig(templateHtml: String): LinkedNoteConfig? =
    LINKED_NOTE_CONFIG_SCRIPT_REGEX
        .findAll(templateHtml)
        .mapNotNull { parseTemplateLinkedNoteConfigFromJson(it.groupValues[1]) }
        .firstOrNull()

private fun parseTemplateLinkedNoteConfigFromJson(rawJson: String): LinkedNoteConfig? {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) return null
    return try {
        val json = JSONObject(trimmed)
        val linkedNoteField = json.optString("linkedNoteField").trim()
        require(linkedNoteField.isNotBlank()) {
            "ankidroid-linked-note-config requires a non-empty linkedNoteField."
        }
        val labelFields =
            buildList {
                val labelArray = json.optJSONArray("labelFields")
                if (labelArray != null) {
                    for (i in 0 until labelArray.length()) {
                        val value = labelArray.optString(i).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }
                val singleLabelField = json.optString("labelField").trim()
                if (singleLabelField.isNotBlank()) {
                    add(singleLabelField)
                }
            }.distinct()
        LinkedNoteConfig(
            linkedNoteField = linkedNoteField,
            deck = json.optString("deck").trim().ifBlank { null },
            searchField = json.optString("searchField").trim().ifBlank { null },
            labelFields = labelFields,
            maxResults = json.optInt("maxResults", 12).coerceAtLeast(2),
            implicitLinks = parseImplicitLinkRules(json),
        )
    } catch (e: Exception) {
        throw LinkedNoteConfigException("Invalid ankidroid-linked-note-config JSON: ${e.localizedMessage}", e)
    }
}

private fun parseImplicitLinkRules(json: JSONObject): List<ImplicitLinkRule> {
    val array = json.optJSONArray("implicitLinks") ?: return emptyList()
    return List(array.length()) { i ->
        val rule = array.getJSONObject(i)
        val pattern = rule.optString("pattern").trim()
        val toDeck = rule.optString("toDeck").trim()
        val toField = rule.optString("toField").trim()
        require(pattern.isNotBlank() && toDeck.isNotBlank() && toField.isNotBlank()) {
            "implicitLinks entries require pattern, toDeck and toField."
        }
        val regex = Regex(pattern)
        require(regex.toPattern().matcher("").groupCount() >= 1) {
            "implicitLinks pattern needs a group for the text to look up: $pattern"
        }
        ImplicitLinkRule(
            fromDeck = rule.optString("fromDeck").trim().ifBlank { null },
            sourceField = rule.optString("sourceField").trim().ifBlank { null },
            pattern = regex,
            toDeck = toDeck,
            toField = toField,
            fallbackField = rule.optString("fallbackField").trim().ifBlank { null },
        )
    }
}

fun resolveLinkedNoteRelation(
    col: Collection,
    note: Note,
): LinkedNoteRelation? {
    val config = parseTemplateLinkedNoteConfig(note.notetype) ?: return null
    if (!note.contains(config.linkedNoteField)) return null
    val linkedGuid = extractLinkedNoteGuid(note.getItem(config.linkedNoteField))
    if (linkedGuid.isBlank()) return resolveImplicitLinkRelation(col, note, config)
    if (linkedGuid == note.guId) return null

    val linkedNoteId =
        runCatching {
            col.db.queryLongScalar("SELECT id FROM notes WHERE guid = ?", linkedGuid)
        }.getOrNull()
            ?: return null
    if (linkedNoteId == note.id) return null

    return runCatching { col.getNote(linkedNoteId) }
        .getOrNull()
        ?.let { linkedNote ->
            LinkedNoteRelation(
                config = config,
                linkedNoteGuid = linkedGuid,
                linkedNote = linkedNote,
            )
        }
}

private val HTML_TAG_REGEX = Regex("<[^>]*>")

/** The text [rule] looks up for a note whose source field is [sourceValue], or null if it doesn't apply */
fun implicitLinkKey(
    rule: ImplicitLinkRule,
    sourceValue: String,
): String? {
    val text = HTML_TAG_REGEX.replace(sourceValue, "").replace("&nbsp;", " ").trim()
    val match = rule.pattern.find(text) ?: return null
    return match.groupValues[1].trim().ifEmpty { null }
}

/** Used only when the linked note field is empty, so a stored link always wins */
private fun resolveImplicitLinkRelation(
    col: Collection,
    note: Note,
    config: LinkedNoteConfig,
): LinkedNoteRelation? {
    for (rule in config.implicitLinks) {
        val sourceField = rule.sourceField ?: config.searchField ?: note.notetype.fieldsNames.first()
        if (!note.contains(sourceField)) continue
        val key = implicitLinkKey(rule, note.getItem(sourceField)) ?: continue
        if (rule.fromDeck != null && !noteHasCardsInDeck(col, note, rule.fromDeck)) continue
        val target = findImplicitLinkTarget(col, note, rule, key) ?: continue
        return LinkedNoteRelation(
            config = config,
            linkedNoteGuid = target.guId.orEmpty(),
            linkedNote = target,
            implicitSourceField = sourceField,
        )
    }
    return null
}

/** Cards in a filtered deck count for their home deck */
private fun noteHasCardsInDeck(
    col: Collection,
    note: Note,
    deckName: String,
): Boolean {
    if (note.id == 0L) return false
    val deckId = col.decks.idForName(deckName) ?: return false
    val ids = Utils.ids2str(col.decks.deckAndChildIds(deckId).toLongArray())
    return col.db.queryScalar("SELECT count() FROM cards WHERE nid = ? AND (did IN $ids OR odid IN $ids)", note.id) > 0
}

/** The single note in the target deck whose field equals [key]; none if there are several */
private fun findImplicitLinkTarget(
    col: Collection,
    note: Note,
    rule: ImplicitLinkRule,
    key: String,
): Note? {
    for (fieldName in listOfNotNull(rule.toField, rule.fallbackField).distinct()) {
        val ids =
            runCatching {
                // the backend escapes the deck name and the field text
                val fieldNode =
                    field {
                        this.fieldName = fieldName
                        text = key
                    }
                val query =
                    col.buildSearchString(
                        listOf(
                            searchNode { deck = rule.toDeck },
                            searchNode { field = fieldNode },
                        ),
                    )
                col.findNotes(query).filter { it != note.id }
            }.getOrElse { return null }
        when (ids.size) {
            0 -> continue
            1 -> return runCatching { col.getNote(ids.single()) }.getOrNull()
            else -> return null
        }
    }
    return null
}

/** What [emptyFieldsMatchingLinkedNote] did to a note */
data class LinkedFieldCleanup(
    val emptied: List<String>,
    val differing: List<String>,
)

private val SOUND_PAIR_REGEX = Regex("""\{([^{}\r\n]+)\}\{\*?\[sound:([^\]\r\n]+)\]\*?\}""")
private val LINE_BREAK_REGEX = Regex("""<br\s*/?>|</?div>|&nbsp;""", RegexOption.IGNORE_CASE)
private val HIDDEN_TEXT_MARKER_REGEX = Regex("""\(\*\d*\)""")
private val WHITESPACE_REGEX = Regex("""\s+""")

/**
 * Field text without markup that doesn't change the content: line-break tags, spacing,
 * `{x.mp3}{*[sound:x.mp3]*}` audio wrappers and hidden-text markers such as "(*1)".
 * Links, formatting and wording still count.
 */
fun normalizeForLinkedComparison(value: String): String =
    value
        .replace(SOUND_PAIR_REGEX) { m ->
            if (m.groupValues[1] == m.groupValues[2]) "[sound:${m.groupValues[2]}]" else m.value
        }.replace(LINE_BREAK_REGEX, " ")
        .replace(HIDDEN_TEXT_MARKER_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

/**
 * Empties each field of [note] that shows the same as in the linked note, so it is filled from the
 * linked note when displayed. Fields that differ are kept and listed. The linked note field, the
 * search field and the implicit link's source field are never touched. Changes [note] in memory only.
 */
fun emptyFieldsMatchingLinkedNote(
    note: Note,
    relation: LinkedNoteRelation,
): LinkedFieldCleanup {
    val keep = setOfNotNull(relation.config.linkedNoteField, relation.config.searchField, relation.implicitSourceField)
    val emptied = mutableListOf<String>()
    val differing = mutableListOf<String>()
    for (fieldName in note.notetype.fieldsNames) {
        if (fieldName in keep) continue
        val own = note.getItem(fieldName)
        if (own.isBlank()) continue
        val linked = if (relation.linkedNote.contains(fieldName)) relation.linkedNote.getItem(fieldName) else ""
        if (normalizeForLinkedComparison(own) == normalizeForLinkedComparison(linked)) {
            note.setItem(fieldName, "")
            emptied += fieldName
        } else {
            differing += fieldName
        }
    }
    return LinkedFieldCleanup(emptied, differing)
}

fun buildEffectiveLinkedNote(
    note: Note,
    relation: LinkedNoteRelation,
): Note {
    val clone = note.clone()
    clone.notetype = note.notetype
    clone.tags = note.tags.toMutableList()
    clone.fields = note.fields.toMutableList()

    val fieldNames = note.notetype.fieldsNames
    for (fieldName in fieldNames) {
        if (fieldName == relation.config.linkedNoteField) {
            clone[fieldName] = ""
            continue
        }
        if (fieldName == relation.config.searchField) {
            if (relation.linkedNote.contains(fieldName)) {
                clone[fieldName] = relation.linkedNote.getItem(fieldName)
            }
            continue
        }
        val currentValue = clone.getItem(fieldName)
        if (currentValue.trim().isNotEmpty()) {
            continue
        }
        if (!relation.linkedNote.contains(fieldName)) {
            continue
        }
        val linkedValue = relation.linkedNote.getItem(fieldName)
        if (linkedValue.trim().isNotEmpty()) {
            clone[fieldName] = linkedValue
        }
    }
    return clone
}

operator fun Note.set(
    fieldName: String,
    value: String,
) {
    setItem(fieldName, value)
}

fun extractLinkedNoteGuid(rawValue: String): String {
    val trimmed = rawValue.trim()
    if (trimmed.isBlank()) return ""
    val match = LINKED_NOTE_GUID_STORAGE_REGEX.find(trimmed)
    val token =
        match
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
            .ifBlank { trimmed }
    return decodeLinkedNoteGuidToken(token)
}

fun formatLinkedNoteStoredValue(
    guid: String,
    summary: String,
): String =
    buildString {
        append('{')
        append(encodeLinkedNoteGuidToken(guid))
        append('}')
        if (summary.isNotBlank()) {
            append(' ')
            append(summary)
        }
    }

private fun encodeLinkedNoteGuidToken(guid: String): String =
    LINKED_NOTE_GUID_HEX_PREFIX +
        buildString {
            for (byte in guid.toByteArray(StandardCharsets.UTF_8)) {
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }

private fun decodeLinkedNoteGuidToken(token: String): String {
    if (!token.startsWith(LINKED_NOTE_GUID_HEX_PREFIX)) {
        return token
    }

    val encoded = token.removePrefix(LINKED_NOTE_GUID_HEX_PREFIX)
    if (encoded.length % 2 != 0 || encoded.any { it.digitToIntOrNull(16) == null }) {
        return token
    }

    val bytes =
        ByteArray(encoded.length / 2) { index ->
            encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    return String(bytes, StandardCharsets.UTF_8)
}
