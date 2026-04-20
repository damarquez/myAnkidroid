package com.ichi2.anki.linkednotes

import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.LinkedNoteDisplayMode
import com.ichi2.anki.libanki.resolveLinkedNoteRelation

fun injectLinkedNoteBanner(
    col: Collection,
    card: Card,
    html: String,
    linkedNoteDisplayMode: LinkedNoteDisplayMode,
): String {
    val note = card.note(col)
    val relation = runCatching { resolveLinkedNoteRelation(col, note) }.getOrNull() ?: return html
    val buttonLabel =
        when (linkedNoteDisplayMode) {
            LinkedNoteDisplayMode.MERGED -> "Show original"
            LinkedNoteDisplayMode.ORIGINAL -> "Show merged"
        }
    val modeLabel =
        when (linkedNoteDisplayMode) {
            LinkedNoteDisplayMode.MERGED -> "Linked note view"
            LinkedNoteDisplayMode.ORIGINAL -> "Original note view"
        }
    val bannerHtml =
        """
        <div class="ankidroid-linked-note-banner">
          <span class="ankidroid-linked-note-label">$modeLabel</span>
          <a class="ankidroid-linked-note-toggle" href="signal:toggle_linked_note_mode">$buttonLabel</a>
        </div>
        """.trimIndent()
    val styleClose = "</style>"
    val insertAt = html.indexOf(styleClose)
    return if (insertAt >= 0) {
        buildString {
            append(html.substring(0, insertAt + styleClose.length))
            append(bannerHtml)
            append(html.substring(insertAt + styleClose.length))
        }
    } else {
        bannerHtml + html
    }
}
