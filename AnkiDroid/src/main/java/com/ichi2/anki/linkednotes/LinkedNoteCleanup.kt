package com.ichi2.anki.linkednotes

import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.emptyFieldsMatchingLinkedNote
import com.ichi2.anki.libanki.resolveLinkedNoteRelation

/** Notes whose field still differs from the linked note get "linked-differs::<Field_Name>" */
const val LINKED_DIFFERS_TAG_PREFIX = "linked-differs::"

data class LinkedNoteCleanupSummary(
    val notesChanged: Int,
    val fieldsEmptied: Int,
    val notesDiffering: Int,
    val notesWithoutLink: Int,
)

/**
 * For each note with a linked note (stored or implicit), empties the fields that show the same as
 * in the linked note and replaces its "linked-differs::" tags with the fields that still differ.
 * Returns the notes to save; nothing is written here.
 */
fun Collection.prepareLinkedNoteCleanup(noteIds: List<NoteId>): Pair<List<Note>, LinkedNoteCleanupSummary> {
    val changed = mutableListOf<Note>()
    var fieldsEmptied = 0
    var notesDiffering = 0
    var notesWithoutLink = 0
    for (noteId in noteIds.distinct()) {
        val note = getNote(noteId)
        val relation = runCatching { resolveLinkedNoteRelation(this, note) }.getOrNull()
        if (relation == null) {
            notesWithoutLink++
            continue
        }
        val oldTags = note.tags.toList()
        val result = emptyFieldsMatchingLinkedNote(note, relation)
        note.tags.filter { it.startsWith(LINKED_DIFFERS_TAG_PREFIX, ignoreCase = true) }.forEach(note::removeTag)
        result.differing.forEach { note.addTag(LINKED_DIFFERS_TAG_PREFIX + it.replace(' ', '_')) }
        fieldsEmptied += result.emptied.size
        if (result.differing.isNotEmpty()) notesDiffering++
        if (result.emptied.isNotEmpty() || note.tags.toSet() != oldTags.toSet()) changed += note
    }
    return changed to LinkedNoteCleanupSummary(changed.size, fieldsEmptied, notesDiffering, notesWithoutLink)
}
