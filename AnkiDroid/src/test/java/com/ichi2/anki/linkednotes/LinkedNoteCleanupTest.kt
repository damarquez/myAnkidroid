/*
 *  Copyright (c) 2026
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.linkednotes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.libanki.ImplicitLinkRule
import com.ichi2.anki.libanki.LinkedSourcePlan
import com.ichi2.testutils.JvmTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LinkedNoteCleanupTest : JvmTest() {
    @Test
    fun `other tags are kept, only stale linked-differs tags are replaced`() {
        val config =
            """
            <script id="ankidroid-linked-note-config" type="application/json">
            { "linkedNoteField": "Linked Note", "searchField": "Front",
              "implicitLinks": [ { "fromDeck": "Lang::Words", "pattern": "^(.)\\.$", "toDeck": "Lang::Chars", "toField": "Front" } ] }
            </script>
            """.trimIndent()
        addStandardNoteType("Scene", arrayOf("Front", "Back", "Pinyin", "Linked Note"), "$config{{Front}}", "{{Back}}")
        val noteType = col.notetypes.byName("Scene")!!
        val chars = addDeck("Lang::Chars")
        val words = addDeck("Lang::Words")
        col.newNote(noteType).also {
            it.setItem("Front", "他")
            it.setItem("Back", "he")
            it.setItem("Pinyin", "tā")
            col.addNote(it, chars)
        }
        val word =
            col.newNote(noteType).also {
                it.setItem("Front", "他.")
                it.setItem("Back", "he")
                it.setItem("Pinyin", "ta1")
                it.tags = mutableListOf("HSK1", "my::own", "linked-differs::Back")
                col.addNote(it, words)
            }

        val (notes, _) = col.prepareLinkedNoteCleanup(listOf(word.id))
        col.updateNotes(notes)

        assertEquals(
            listOf("HSK1", "linked-differs::Pinyin", "my::own"),
            col.getNote(word.id).tags.sorted(),
        )
    }

    @Test
    fun `a max rank keeps the words ranked at most that, and no max rank keeps all`() {
        val rule = ImplicitLinkRule(null, null, Regex("^(.)\\.$"), "Chars", "Front", null)
        val target = col.newNote(col.notetypes.byName("Basic")!!)

        fun word(rank: Long?) =
            LinkedWordToCreate(
                target,
                LinkedSourcePlan(rule, "Words", "Front", "$rank.", emptyList()),
                deckId = 1,
                frequency = rank?.let { NewNoteFrequency("HSK Frequency", "$it", it) },
            )
        val words = listOf(word(8), word(500), word(501), word(null))

        assertEquals(listOf("8.", "500."), words.withRankAtMost(500).map { it.plan.sourceValue })
        assertEquals(words, words.withRankAtMost(null))
        assertEquals(emptyList(), words.withRankAtMost(0))
    }
}
