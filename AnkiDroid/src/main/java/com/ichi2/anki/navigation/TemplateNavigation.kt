package com.ichi2.anki.navigation

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.browser.search.SearchString
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.libanki.NoteTypeId
import com.ichi2.anki.libanki.SortOrder
import com.ichi2.anki.model.CardsOrNotes
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.anki.ranking.FREQUENCY_FORMAT_RANK_AND_TERM
import com.ichi2.anki.ranking.FREQUENCY_RANK_TYPE_AUTO
import com.ichi2.anki.ranking.TemplateFrequencyLookupRule
import com.ichi2.anki.ranking.WordRankingStore
import com.ichi2.anki.ranking.formatFrequency
import com.ichi2.anki.ranking.parseTemplateFrequencyLookupRules
import com.ichi2.anki.ranking.rankFor
import com.ichi2.anki.ranking.sanitizeRankingLookupTerm
import com.ichi2.anki.searchForRows
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

const val NAVIGATION_OPEN_MODE_ANSWER = "answer"
const val NAVIGATION_OPEN_MODE_QUESTION = "question"
const val NAVIGATION_OPEN_MODE_SHARE = "share"

data class NavigationSearchSpec(
    val deck: String,
    val field: String,
    val fallbackField: String,
    val matchMode: String,
    val prefix: String,
    val suffix: String,
    /** Note type used when offering to create a missing note; blank = the type most used in [deck] */
    val noteType: String = "",
    /** When false, a search without results just shows "no results" instead of offering to create a note */
    val offerCreate: Boolean = true,
)

data class NavigationShareSpec(
    val prefix: String,
    val suffix: String,
)

data class NavigationRequest(
    val query: String,
    val openMode: String,
    val selectedText: String,
    val search: NavigationSearchSpec?,
    val share: NavigationShareSpec?,
)

data class NavigationMatch(
    val cardId: Long,
    val noteId: Long,
    val preview: String,
    val deckName: String,
)

fun parseNavigationRequest(payload: String): NavigationRequest {
    val trimmed = payload.trim()
    if (trimmed.isBlank()) {
        return NavigationRequest("", NAVIGATION_OPEN_MODE_QUESTION, "", null, null)
    }
    return runCatching {
        val data = JSONObject(trimmed)
        val searchData = data.optJSONObject("search")
        val shareData = data.optJSONObject("share")
        NavigationRequest(
            query = data.optString("query", "").trim(),
            openMode =
                data
                    .optString("openMode", NAVIGATION_OPEN_MODE_QUESTION)
                    .trim()
                    .lowercase()
                    .ifBlank { NAVIGATION_OPEN_MODE_QUESTION },
            selectedText = data.optString("selectedText", "").trim(),
            search =
                searchData?.let {
                    NavigationSearchSpec(
                        deck = it.optString("deck", "").trim(),
                        field = it.optString("field", "Front").trim().ifBlank { "Front" },
                        fallbackField = it.optString("fallbackField", "").trim(),
                        matchMode =
                            it
                                .optString("matchMode", "exact")
                                .trim()
                                .lowercase()
                                .ifBlank { "exact" },
                        prefix = it.optString("prefix", ""),
                        suffix = it.optString("suffix", ""),
                        noteType = it.optString("noteType", "").trim(),
                        offerCreate = it.optBoolean("offerCreate", true),
                    )
                },
            share =
                shareData?.let {
                    NavigationShareSpec(
                        prefix = it.optString("prefix", ""),
                        suffix = it.optString("suffix", ""),
                    )
                },
        )
    }.getOrElse {
        NavigationRequest(trimmed, NAVIGATION_OPEN_MODE_QUESTION, "", null, null)
    }
}

suspend fun loadKnownDeckNames(): List<String> = withCol { decks.allNamesAndIds().map { it.name } }

fun buildKnownDecksInjectionJs(names: List<String>): String = "window.__ankidroidKnownDecks = ${JSONArray(names)};"

suspend fun findNavigationMatches(query: String): List<NavigationMatch> {
    val searchString = withCol { SearchString.fromUserInput(query) }.getOrThrow()
    val cards =
        searchForRows(searchString, SortOrder.UseCollectionOrdering, CardsOrNotes.CARDS)
            .map { withCol { getCard(it.cardOrNoteId) } }

    return cards
        .groupBy { it.nid }
        .values
        .map { matchesForNote ->
            val primaryCard = matchesForNote.minWith(compareBy<Card> { it.ord }.thenBy { it.id })
            val preview =
                withCol {
                    primaryCard
                        .note(this)
                        .fields
                        .firstOrNull()
                        .orEmpty()
                }
            val deckName = withCol { Decks.basename(decks.name(primaryCard.did)) }
            NavigationMatch(
                cardId = primaryCard.id,
                noteId = primaryCard.nid,
                preview = preview,
                deckName = deckName,
            )
        }.distinctBy { it.noteId }
}

private fun escapeForAnkiSearch(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

private fun buildFieldQuery(
    selectedText: String,
    search: NavigationSearchSpec,
    fieldName: String,
): String {
    val escapedDeck = escapeForAnkiSearch(search.deck)
    val normalizedText = "${search.prefix}${selectedText}${search.suffix}"
    val escapedText = escapeForAnkiSearch(normalizedText)
    val matchMode = search.matchMode.ifBlank { "exact" }
    val fieldClause =
        if (matchMode == "partial") {
            "$fieldName:*$escapedText*"
        } else {
            "$fieldName:\"$escapedText\""
        }
    return "deck:\"$escapedDeck\" $fieldClause"
}

private fun canUseStructuredSearch(request: NavigationRequest): Boolean =
    !request.selectedText.isBlank() &&
        request.search != null &&
        request.search.deck.isNotBlank() &&
        request.search.field.isNotBlank()

suspend fun findNavigationMatches(request: NavigationRequest): List<NavigationMatch> {
    if (!canUseStructuredSearch(request)) {
        return findNavigationMatches(request.query)
    }

    val search = request.search ?: return findNavigationMatches(request.query)
    val primaryQuery = buildFieldQuery(request.selectedText, search, search.field)
    val primaryMatches = findNavigationMatches(primaryQuery)
    if (primaryMatches.isNotEmpty()) {
        return primaryMatches
    }

    val fallbackField = search.fallbackField.trim()
    if (fallbackField.isBlank() || fallbackField.equals(search.field, ignoreCase = true)) {
        return primaryMatches
    }
    val fallbackQuery = buildFieldQuery(request.selectedText, search, fallbackField)
    return findNavigationMatches(fallbackQuery)
}

/** Where a note created from a failed "deck" navigation search would go, and what it is prefilled with */
data class NavigationCreateTarget(
    val deckId: DeckId,
    val deckName: String,
    val noteTypeId: NoteTypeId,
    val noteTypeName: String,
    val fieldName: String,
    val fieldValue: String,
    /** Set when the configured `noteType` doesn't exist and another type was picked */
    val noteTypeWarning: String? = null,
    /** The note type's `ankidroid-frequency-config` rule for this deck and field, if any */
    val frequencyRule: TemplateFrequencyLookupRule? = null,
)

suspend fun resolveNavigationCreateTarget(request: NavigationRequest): NavigationCreateTarget? {
    if (!canUseStructuredSearch(request)) return null
    val search = request.search ?: return null
    if (!search.offerCreate) return null

    return withCol {
        val deckId = decks.idForName(search.deck) ?: return@withCol null
        if (decks.isFiltered(deckId)) return@withCol null

        val configured = search.noteType.ifBlank { null }?.let { notetypes.byName(it) }
        val noteType =
            configured
                ?: run {
                    // Same scope as the search: the deck and its subdecks
                    val deckIds = decks.deckAndChildIds(deckId).joinToString(",")
                    val mostUsedId =
                        db.queryLongScalar(
                            "SELECT n.mid FROM cards c JOIN notes n ON n.id = c.nid " +
                                "WHERE c.did IN ($deckIds) OR c.odid IN ($deckIds) " +
                                "GROUP BY n.mid ORDER BY count(*) DESC LIMIT 1",
                        )
                    notetypes.get(mostUsedId)
                }
                ?: notetypes.current()
        val fieldName =
            noteType.fieldsNames.firstOrNull { it.equals(search.field, ignoreCase = true) }
                ?: noteType.fieldsNames.first()
        val deckName = decks.name(deckId)
        // Same rule choice as the editor's frequency button: deck-specific first, then generic
        val frequencyRule =
            runCatching { parseTemplateFrequencyLookupRules(noteType) }
                .onFailure { Timber.w(it, "Invalid frequency config") }
                .getOrDefault(emptyList())
                .filter { it.sourceField == fieldName }
                .let { rules -> rules.firstOrNull { it.deck == deckName } ?: rules.firstOrNull { it.deck == null } }
        NavigationCreateTarget(
            deckId = deckId,
            deckName = deckName,
            noteTypeId = noteType.id,
            noteTypeName = noteType.name,
            fieldName = fieldName,
            fieldValue = "${search.prefix}${request.selectedText}${search.suffix}",
            noteTypeWarning =
                if (search.noteType.isNotBlank() && configured == null) {
                    "Note type \"${search.noteType}\" not found."
                } else {
                    null
                },
            frequencyRule = frequencyRule,
        )
    }
}

/**
 * The frequency the note would get, computed like the editor's "HSK Frequency" button. Without a
 * config rule, it uses "auto" (char rank for a character, global rank for a word or "好.").
 * Returns a "label: value" line for the dialog.
 */
private suspend fun describeNavigationFrequency(
    context: Context,
    target: NavigationCreateTarget,
): String {
    val rule =
        target.frequencyRule ?: TemplateFrequencyLookupRule(
            field = null,
            deck = null,
            sourceField = target.fieldName,
            targetField = null,
            format = FREQUENCY_FORMAT_RANK_AND_TERM,
            rankType = FREQUENCY_RANK_TYPE_AUTO,
            removeNonAlphabeticChars = true,
        )
    val label = rule.targetField ?: rule.field ?: "Frequency"
    val source = target.fieldValue
    val term = sanitizeRankingLookupTerm(source, rule.removeNonAlphabeticChars).trim()
    val store = WordRankingStore(context)
    if (!store.status().ready) return "$label: ranking data not set up"
    val lookup = if (term.isBlank()) null else store.lookup(term)
    val rank = lookup?.let { rule.rankFor(it, source) } ?: return "$label: not ranked"
    return "$label: ${rule.formatFrequency(rank, lookup, source)}"
}

/**
 * Called when a navigation search found nothing. For "deck" searches, offers to open the Add note
 * screen in the searched deck, prefilled from the nav config. Nothing is stored unless the user
 * saves the note there. Otherwise, or if no note can be created, [onNoMatch] is called.
 */
suspend fun offerToCreateNavigationNote(
    context: Context,
    request: NavigationRequest,
    onNoMatch: () -> Unit,
) {
    val target =
        runCatching { resolveNavigationCreateTarget(request) }
            .onFailure { Timber.w(it, "Could not resolve a note to create for navigation") }
            .getOrNull()
    if (target == null) {
        onNoMatch()
        return
    }
    val frequency =
        runCatching { describeNavigationFrequency(context, target) }
            .onFailure { Timber.w(it, "Frequency lookup failed") }
            .getOrNull()
    val message =
        buildString {
            appendLine("\"${target.fieldValue}\" was not found.")
            appendLine()
            appendLine("Create a new note?")
            appendLine("Deck: ${target.deckName}")
            target.noteTypeWarning?.let { appendLine(it) }
            appendLine("Note type: ${target.noteTypeName}")
            append("${target.fieldName}: ${target.fieldValue}")
            frequency?.let { append("\n$it") }
        }
    AlertDialog
        .Builder(context)
        .setTitle("No match")
        .setMessage(message)
        .setPositiveButton("Create") { _, _ ->
            Timber.i("Navigation: opening note editor to create '%s'", target.fieldValue)
            val launcher =
                NoteEditorLauncher.AddNoteFromNavigation(
                    deckId = target.deckId,
                    noteTypeId = target.noteTypeId,
                    fieldName = target.fieldName,
                    fieldValue = target.fieldValue,
                )
            context.startActivity(launcher.toIntent(context))
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}
