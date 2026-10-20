package com.ichi2.anki.linkednotes

import anki.collection.OpChanges
import anki.notes.addNoteRequest
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.LinkedSourcePlan
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.libanki.backend.model.toBackendNote
import com.ichi2.anki.libanki.newLinkedSourceNote
import com.ichi2.anki.libanki.planLinkedSourceNote
import com.ichi2.anki.ranking.WordRankingStore
import com.ichi2.anki.ranking.formatFrequency
import com.ichi2.anki.ranking.parseTemplateFrequencyLookupRules
import com.ichi2.anki.ranking.rankFor
import com.ichi2.anki.ranking.sanitizeRankingLookupTerm
import timber.log.Timber

/** A frequency to store in [field]: [value] is the formatted text, e.g. "00127 冷." for [rank] 127 */
data class NewNoteFrequency(
    val field: String,
    val value: String,
    val rank: Long,
)

/**
 * A note to add that links back to [target] and has only its source field, e.g. Vocabulary "好."
 * for Characters "好". [frequency] is set before adding.
 */
class LinkedWordToCreate(
    val target: Note,
    val plan: LinkedSourcePlan,
    val deckId: DeckId,
    var frequency: NewNoteFrequency? = null,
)

/** The words ranked [maxRank] or more frequent; all of them when [maxRank] is null. Unranked words are left out. */
fun List<LinkedWordToCreate>.withRankAtMost(maxRank: Long?): List<LinkedWordToCreate> =
    if (maxRank == null) this else filter { word -> word.frequency?.let { it.rank <= maxRank } == true }

data class LinkedWordPlans(
    val toCreate: List<LinkedWordToCreate>,
    /** Source texts that already exist, e.g. "好." */
    val alreadyExisting: List<String>,
    /** Notes no implicit link rule creates anything for */
    val notApplicable: Int,
)

/** What [addLinkedWordNotes] would add for the selected notes; nothing is written here */
fun Collection.planLinkedWordNotes(noteIds: List<NoteId>): LinkedWordPlans {
    val toCreate = mutableListOf<LinkedWordToCreate>()
    val existing = mutableListOf<String>()
    var notApplicable = 0
    for (noteId in noteIds.distinct()) {
        val target = getNote(noteId)
        val plan = runCatching { planLinkedSourceNote(this, target) }.getOrNull()
        val deckId = plan?.let { decks.idForName(it.deckName) }
        when {
            plan == null || deckId == null || decks.isFiltered(deckId) -> notApplicable++
            plan.existingNoteIds.isNotEmpty() || toCreate.any { it.plan.sourceValue == plan.sourceValue } ->
                existing += plan.sourceValue
            else -> toCreate += LinkedWordToCreate(target, plan, deckId)
        }
    }
    return LinkedWordPlans(toCreate, existing, notApplicable)
}

/** Adds the notes in one undo step and returns the changes */
fun Collection.addLinkedWordNotes(words: List<LinkedWordToCreate>): OpChanges {
    val notes =
        words.map { word ->
            newLinkedSourceNote(this, word.target, word.plan).also { note ->
                word.frequency?.let { note.setItem(it.field, it.value) }
            }
        }
    val out =
        backend.addNotes(
            notes.zip(words).map { (note, word) ->
                addNoteRequest {
                    this.note = note.toBackendNote()
                    deckId = word.deckId
                }
            },
        )
    notes.forEachIndexed { i, note -> note.id = out.getNids(i) }
    return out.changes
}

/**
 * What the editor's frequency button would fill for a new note with [sourceValue] in [sourceField]
 * and going to [deckName]. Null without a matching rule, ranking data or rank.
 */
suspend fun frequencyForNewNote(
    store: WordRankingStore,
    noteType: NotetypeJson,
    deckName: String,
    sourceField: String,
    sourceValue: String,
): NewNoteFrequency? {
    // Same rule choice as the navigation create dialog: deck-specific first, then generic
    val rule =
        runCatching { parseTemplateFrequencyLookupRules(noteType) }
            .onFailure { Timber.w(it, "Invalid frequency config") }
            .getOrDefault(emptyList())
            .filter { it.sourceField == sourceField }
            .let { rules -> rules.firstOrNull { it.deck == deckName } ?: rules.firstOrNull { it.deck == null } }
            ?: return null
    val targetField = (rule.targetField ?: rule.field)?.takeIf { it in noteType.fieldsNames } ?: return null
    val term = sanitizeRankingLookupTerm(sourceValue, rule.removeNonAlphabeticChars).trim()
    if (term.isBlank() || !store.status().ready) return null
    val lookup = store.lookup(term) ?: return null
    val rank = rule.rankFor(lookup, sourceValue) ?: return null
    return NewNoteFrequency(targetField, rule.formatFrequency(rank, lookup, sourceValue), rank)
}

/** Fills [LinkedWordToCreate.frequency]; returns how many got none */
suspend fun fillLinkedWordFrequencies(
    store: WordRankingStore,
    words: List<LinkedWordToCreate>,
): Int {
    var missing = 0
    for (word in words) {
        word.frequency =
            runCatching {
                frequencyForNewNote(store, word.target.notetype, word.plan.deckName, word.plan.sourceField, word.plan.sourceValue)
            }.onFailure { Timber.w(it, "Frequency lookup failed") }
                .getOrNull()
        if (word.frequency == null) missing++
    }
    return missing
}
