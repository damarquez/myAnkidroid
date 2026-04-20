package com.ichi2.anki.notelinks

import android.net.Uri
import android.text.TextUtils
import com.ichi2.anki.CollectionManager.withCol

private val NOTE_LINK_REGEX = Regex("""\[link:(?:"([^"\r\n]+)"|([^|\]\r\n]+))\|([^\]]+)]""")

fun expandNoteLinksToHtml(content: String): String =
    NOTE_LINK_REGEX.replace(content) { match ->
        val guid = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
        val label = match.groupValues[3]
        if (guid.isBlank() || label.isBlank()) {
            match.value
        } else {
            """<a href="ankidroid-note://open?guid=${Uri.encode(guid)}">${TextUtils.htmlEncode(label)}</a>"""
        }
    }

fun formatNoteLinkMarkup(
    guid: String,
    label: String,
): String = "[link:\"${guid.trim()}\"|$label]"

fun isNoteLinkMarkupTextSupported(text: String): Boolean = !text.contains(']') && !text.contains('|')

fun extractGuidFromNoteLinkUrl(uri: Uri): String? {
    if (uri.scheme != "ankidroid-note" || uri.host != "open") {
        return null
    }
    val guid = uri.getQueryParameter("guid")?.trim().orEmpty()
    return guid.ifBlank { null }
}

suspend fun findFirstCardIdForNoteGuid(guid: String): Long? =
    withCol {
        val noteId =
            runCatching {
                db.queryLongScalar("SELECT id FROM notes WHERE guid = ?", guid)
            }.getOrNull()
                ?: return@withCol null
        val note = runCatching { getNote(noteId) }.getOrNull() ?: return@withCol null
        note.cardIds(this).firstOrNull()
    }
