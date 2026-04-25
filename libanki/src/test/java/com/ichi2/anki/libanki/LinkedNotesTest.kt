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
}
