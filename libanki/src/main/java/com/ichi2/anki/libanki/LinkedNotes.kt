package com.ichi2.anki.libanki

import org.json.JSONObject

private val LINKED_NOTE_CONFIG_SCRIPT_REGEX =
    Regex(
        """<script[^>]*id\s*=\s*["']ankidroid-linked-note-config["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

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
)

private val LINKED_NOTE_GUID_STORAGE_REGEX = Regex("""\{([^{}\r\n]+)\}""")

data class LinkedNoteRelation(
    val config: LinkedNoteConfig,
    val linkedNoteGuid: String,
    val linkedNote: Note,
)

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
        )
    } catch (e: Exception) {
        throw LinkedNoteConfigException("Invalid ankidroid-linked-note-config JSON: ${e.localizedMessage}", e)
    }
}

fun resolveLinkedNoteRelation(
    col: Collection,
    note: Note,
): LinkedNoteRelation? {
    val config = parseTemplateLinkedNoteConfig(note.notetype) ?: return null
    if (!note.contains(config.linkedNoteField)) return null
    val linkedGuid = extractLinkedNoteGuid(note.getItem(config.linkedNoteField))
    if (linkedGuid.isBlank()) return null
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
    return match?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { trimmed }
}
