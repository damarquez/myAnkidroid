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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LinkedNotesTest {
    @Test
    fun `formatted linked note guids round trip when they contain braces`() {
        val guid = "a{b}c|d"

        val storedValue = formatLinkedNoteStoredValue(guid, "summary")

        assertEquals(guid, extractLinkedNoteGuid(storedValue))
    }

    @Test
    fun `raw legacy linked note guids are still supported`() {
        assertEquals("abc123", extractLinkedNoteGuid("{abc123} summary"))
        assertEquals("abc123", extractLinkedNoteGuid("abc123"))
    }

    @Test
    fun `implicit link rules are parsed`() {
        val config =
            parseTemplateLinkedNoteConfig(
                """
                <script id="ankidroid-linked-note-config" type="application/json">
                {
                  "linkedNoteField": "Linked Note",
                  "implicitLinks": [
                    { "fromDeck": "A::Words", "pattern": "^(.)\\.$", "toDeck": "A::Chars", "toField": "Front" }
                  ]
                }
                </script>
                """.trimIndent(),
            )!!

        val rule = config.implicitLinks.single()
        assertEquals("A::Words", rule.fromDeck)
        assertEquals("A::Chars", rule.toDeck)
        assertNull(rule.fallbackField)
    }

    @Test
    fun `implicit link pattern needs a group`() {
        assertThrows(LinkedNoteConfigException::class.java) {
            parseTemplateLinkedNoteConfig(
                """<script id="ankidroid-linked-note-config">{"linkedNoteField": "L",
                  "implicitLinks": [{"pattern": "^.\\.$", "toDeck": "D", "toField": "Front"}]}</script>""",
            )
        }
    }

    @Test
    fun `implicit link key ignores markup and spaces`() {
        val rule = ImplicitLinkRule(null, null, Regex("""^(.)\.$"""), "D", "Front", null)

        assertEquals("好", implicitLinkKey(rule, "好."))
        assertEquals("买", implicitLinkKey(rule, " <b>买.</b>&nbsp;"))
        assertNull(implicitLinkKey(rule, "好"))
        assertNull(implicitLinkKey(rule, "你好."))
    }

    @Test
    fun `source text is derived from the pattern or createAs`() {
        val dotted = ImplicitLinkRule(null, null, Regex("""^(.)\.$"""), "D", "Front", null)
        assertEquals("好.", implicitLinkSourceValue(dotted, "好"))
        assertNull(implicitLinkSourceValue(dotted, "你好"))

        val wrapped = ImplicitLinkRule(null, null, Regex("""^w:\s*(.+)$"""), "D", "Front", null)
        assertNull(implicitLinkSourceValue(wrapped, "好"))
        assertEquals("w: 好", implicitLinkSourceValue(wrapped.copy(createAs = "w: $1"), "好"))
    }

    @Test
    fun `comparison ignores line breaks, audio wrappers and hidden text markers`() {
        assertEquals(
            normalizeForLinkedComparison("hǎo<br>[sound:a.mp3]"),
            normalizeForLinkedComparison("hǎo<br>{a.mp3}{*[sound:a.mp3]*}"),
        )
        assertEquals(
            normalizeForLinkedComparison("1. 你好！(*1)<br>(Nǐ hǎo!)(*2)"),
            normalizeForLinkedComparison("1. 你好！   <br>(Nǐ hǎo!)   "),
        )
        assertNotEquals(
            normalizeForLinkedComparison("""Actor: [link:"x"|h-] (Harrison Ford)"""),
            normalizeForLinkedComparison("Actor: h- (Harrison Ford)"),
        )
    }
}
