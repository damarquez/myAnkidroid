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
package com.ichi2.anki.libanki

import com.ichi2.anki.libanki.testutils.InMemoryAnkiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkedNotesWithColTest : InMemoryAnkiTest() {
    private lateinit var noteType: NotetypeJson
    private var charsDeck: DeckId = 0
    private var wordsDeck: DeckId = 0

    @Before
    fun setUpNoteType() {
        val config =
            """
            <script id="ankidroid-linked-note-config" type="application/json">
            {
              "linkedNoteField": "Linked Note",
              "searchField": "Front",
              "implicitLinks": [
                { "fromDeck": "Lang::Words", "pattern": "^(.)\\.$",
                  "toDeck": "Lang::Chars", "toField": "Front", "fallbackField": "Traditional" }
              ]
            }
            </script>
            """.trimIndent()
        addStandardNoteType("Scene", arrayOf("Front", "Traditional", "Back", "Linked Note"), "$config{{Front}}", "{{Back}}")
        noteType = col.notetypes.byName("Scene")!!
        charsDeck = addDeck("Lang::Chars")
        wordsDeck = addDeck("Lang::Words")
    }

    private fun add(
        deck: DeckId,
        front: String,
        back: String = "",
        traditional: String = "",
        linked: String = "",
    ): Note =
        col.newNote(noteType).also {
            it.setItem("Front", front)
            it.setItem("Traditional", traditional)
            it.setItem("Back", back)
            it.setItem("Linked Note", linked)
            col.addNote(it, deck)
        }

    @Test
    fun `word note links to its character by rule`() {
        val char = add(charsDeck, "好", back = "good")
        val word = add(wordsDeck, "好.")

        val relation = resolveLinkedNoteRelation(col, word)!!

        assertEquals(char.id, relation.linkedNote.id)
        assertTrue(relation.isImplicit)
        assertEquals("good", buildEffectiveLinkedNote(word, relation).getItem("Back"))
    }

    @Test
    fun `fallback field finds a traditional form`() {
        val char = add(charsDeck, "见", traditional = "見")
        val word = add(wordsDeck, "見.")

        assertEquals(char.id, resolveLinkedNoteRelation(col, word)!!.linkedNote.id)
    }

    @Test
    fun `rule only applies in its deck`() {
        add(charsDeck, "好")
        val sameDeck = add(charsDeck, "好.")

        assertNull(resolveLinkedNoteRelation(col, sameDeck))
    }

    @Test
    fun `no link when several notes match`() {
        add(charsDeck, "好")
        add(charsDeck, "好")
        val word = add(wordsDeck, "好.")

        assertNull(resolveLinkedNoteRelation(col, word))
    }

    @Test
    fun `stored link wins over the rule`() {
        add(charsDeck, "好")
        val other = add(charsDeck, "他")
        val word = add(wordsDeck, "好.", linked = formatLinkedNoteStoredValue(other.guId!!, "他"))

        val relation = resolveLinkedNoteRelation(col, word)!!

        assertEquals(other.id, relation.linkedNote.id)
        assertTrue(!relation.isImplicit)
    }

    @Test
    fun `matching fields are emptied and differing ones kept`() {
        add(charsDeck, "好", back = "Good<br>(adjective)", traditional = "好")
        val word = add(wordsDeck, "好.", back = "Good (adjective)", traditional = "好x")
        val relation = resolveLinkedNoteRelation(col, word)!!

        val result = emptyFieldsMatchingLinkedNote(word, relation)

        assertEquals(listOf("Back"), result.emptied)
        assertEquals(listOf("Traditional"), result.differing)
        assertEquals("", word.getItem("Back"))
        assertEquals("好.", word.getItem("Front"))
    }
}
