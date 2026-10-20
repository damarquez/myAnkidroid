package com.ichi2.anki.linkednotes

import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.libanki.emptyFieldsMatchingLinkedNote
import com.ichi2.anki.libanki.linkedNoteStoredValue
import com.ichi2.anki.libanki.resolveLinkedNoteRelation
import com.ichi2.anki.ranking.parseTemplateFrequencyLookupRules

/** Notes whose field still differs from the linked note get "linked-differs::<Field_Name>" */
const val LINKED_DIFFERS_TAG_PREFIX = "linked-differs::"

data class LinkedNoteCleanupSummary(
    val notesChanged: Int,
    val fieldsEmptied: Int,
    val notesDiffering: Int,
    val notesWithoutLink: Int,
    /** Notes linked by an implicit rule whose link is now stored in the linked note field */
    val linksStored: Int,
)

/**
 * For each note with a linked note (stored or implicit), empties the fields that show the same as
 * in the linked note and replaces its "linked-differs::" tags with the fields that still differ.
 * An implicit link is stored in the linked note field, so it shows in the editor and on desktop.
 * Frequency fields are kept and not tagged: a word's rank differs from its character's by design.
 * Returns the notes to save; nothing is written here.
 */
fun Collection.prepareLinkedNoteCleanup(noteIds: List<NoteId>): Pair<List<Note>, LinkedNoteCleanupSummary> {
    val changed = mutableListOf<Note>()
    var fieldsEmptied = 0
    var notesDiffering = 0
    var notesWithoutLink = 0
    var linksStored = 0
    for (noteId in noteIds.distinct()) {
        val note = getNote(noteId)
        val relation = runCatching { resolveLinkedNoteRelation(this, note) }.getOrNull()
        if (relation == null) {
            notesWithoutLink++
            continue
        }
        val oldTags = note.tags.toList()
        val result = emptyFieldsMatchingLinkedNote(note, relation, frequencyFields(note.notetype))
        note.tags.filter { it.startsWith(LINKED_DIFFERS_TAG_PREFIX, ignoreCase = true) }.forEach(note::removeTag)
        result.differing.forEach { note.addTag(LINKED_DIFFERS_TAG_PREFIX + it.replace(' ', '_')) }
        if (relation.isImplicit) {
            note.setItem(relation.config.linkedNoteField, linkedNoteStoredValue(relation.linkedNote, relation.config))
            linksStored++
        }
        fieldsEmptied += result.emptied.size
        if (result.differing.isNotEmpty()) notesDiffering++
        if (result.emptied.isNotEmpty() || relation.isImplicit || note.tags.toSet() != oldTags.toSet()) changed += note
    }
    return changed to LinkedNoteCleanupSummary(changed.size, fieldsEmptied, notesDiffering, notesWithoutLink, linksStored)
}

/** Fields filled by an `ankidroid-frequency-config` rule */
private fun frequencyFields(noteType: NotetypeJson): Set<String> =
    runCatching { parseTemplateFrequencyLookupRules(noteType) }
        .getOrDefault(emptyList())
        .mapNotNull { it.targetField ?: it.field }
        .toSet()
