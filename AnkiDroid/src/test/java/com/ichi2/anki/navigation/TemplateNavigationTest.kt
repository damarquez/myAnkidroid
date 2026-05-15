package com.ichi2.anki.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateNavigationTest {
    @Test
    fun testParseNavigationRequest_Empty() {
        val request = parseNavigationRequest("")
        assertEquals("", request.query)
        assertEquals(NAVIGATION_OPEN_MODE_QUESTION, request.openMode)
        assertEquals("", request.selectedText)
        assertNull(request.search)
        assertNull(request.share)
    }

    @Test
    fun testParseNavigationRequest_Share() {
        val payload =
            """
            {
                "selectedText": "hello",
                "openMode": "share",
                "share": {
                    "prefix": "Pre: ",
                    "suffix": " :Post"
                }
            }
            """.trimIndent()
        val request = parseNavigationRequest(payload)
        assertEquals("", request.query)
        assertEquals(NAVIGATION_OPEN_MODE_SHARE, request.openMode)
        assertEquals("hello", request.selectedText)
        assertNull(request.search)
        assertNotNull(request.share)
        assertEquals("Pre: ", request.share?.prefix)
        assertEquals(" :Post", request.share?.suffix)
    }

    @Test
    fun testParseNavigationRequest_Search() {
        val payload =
            """
            {
                "query": "deck:Default",
                "openMode": "answer",
                "selectedText": "search me",
                "search": {
                    "deck": "Default",
                    "field": "Back",
                    "fallbackField": "Front",
                    "matchMode": "partial",
                    "prefix": "p-",
                    "suffix": "-s"
                }
            }
            """.trimIndent()
        val request = parseNavigationRequest(payload)
        assertEquals("deck:Default", request.query)
        assertEquals(NAVIGATION_OPEN_MODE_ANSWER, request.openMode)
        assertEquals("search me", request.selectedText)
        assertNotNull(request.search)
        assertEquals("Default", request.search?.deck)
        assertEquals("Back", request.search?.field)
        assertEquals("Front", request.search?.fallbackField)
        assertEquals("partial", request.search?.matchMode)
        assertEquals("p-", request.search?.prefix)
        assertEquals("-s", request.search?.suffix)
        assertNull(request.share)
    }

    @Test
    fun testParseNavigationRequest_InvalidJson() {
        val request = parseNavigationRequest("not a json")
        assertEquals("not a json", request.query)
        assertEquals(NAVIGATION_OPEN_MODE_QUESTION, request.openMode)
        assertNull(request.search)
        assertNull(request.share)
    }
}
