package com.ichi2.anki.navigation

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.browser.search.SearchString
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.libanki.SortOrder
import com.ichi2.anki.model.CardsOrNotes
import com.ichi2.anki.searchForRows
import org.json.JSONArray
import org.json.JSONObject

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
