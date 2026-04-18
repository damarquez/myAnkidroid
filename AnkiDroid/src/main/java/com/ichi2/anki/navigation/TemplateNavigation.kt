package com.ichi2.anki.navigation

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.browser.search.SearchString
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.libanki.SortOrder
import com.ichi2.anki.model.CardsOrNotes
import com.ichi2.anki.searchForRows
import org.json.JSONObject

const val NAVIGATION_OPEN_MODE_ANSWER = "answer"
const val NAVIGATION_OPEN_MODE_QUESTION = "question"

data class NavigationRequest(
    val query: String,
    val openMode: String,
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
        return NavigationRequest("", NAVIGATION_OPEN_MODE_QUESTION)
    }
    return runCatching {
        val data = JSONObject(trimmed)
        NavigationRequest(
            query = data.optString("query", "").trim(),
            openMode =
                data
                    .optString("openMode", NAVIGATION_OPEN_MODE_QUESTION)
                    .trim()
                    .lowercase()
                    .ifBlank { NAVIGATION_OPEN_MODE_QUESTION },
        )
    }.getOrElse {
        NavigationRequest(trimmed, NAVIGATION_OPEN_MODE_QUESTION)
    }
}

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
