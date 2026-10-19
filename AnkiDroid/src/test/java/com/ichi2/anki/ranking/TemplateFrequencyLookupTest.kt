package com.ichi2.anki.ranking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateFrequencyLookupTest {
    @Test
    fun parseTemplateFrequencyLookupRule_defaultsToAutoRankAndNoSanitizing() {
        val rules =
            parseTemplateFrequencyLookupRules(
                """
                <script id="ankidroid-frequency-config" type="application/json">
                {"sourceField":"Front"}
                </script>
                """.trimIndent(),
            )

        assertEquals(1, rules.size)
        assertEquals(null, rules.single().deck)
        assertEquals(FREQUENCY_RANK_TYPE_AUTO, rules.single().rankType)
        assertFalse(rules.single().removeNonAlphabeticChars)
    }

    @Test
    fun parseTemplateFrequencyLookupRule_readsExplicitRankTypeAndSanitizeFlag() {
        val rules =
            parseTemplateFrequencyLookupRules(
                """
                <script id="ankidroid-frequency-config" type="application/json">
                {"sourceField":"Front","deck":"Deck B","rankType":"global","removeNonAlphabeticChars":true}
                </script>
                """.trimIndent(),
            )

        assertEquals(1, rules.size)
        assertEquals("Deck B", rules.single().deck)
        assertEquals(FREQUENCY_RANK_TYPE_GLOBAL, rules.single().rankType)
        assertTrue(rules.single().removeNonAlphabeticChars)
    }

    @Test
    fun parseTemplateFrequencyLookupRule_supportsAlphabeticalAlias() {
        val rules =
            parseTemplateFrequencyLookupRules(
                """
                <script id="ankidroid-frequency-config" type="application/json">
                {"sourceField":"Front","removeNonAlphabeticalChars":true}
                </script>
                """.trimIndent(),
            )

        assertEquals(1, rules.size)
        assertTrue(rules.single().removeNonAlphabeticChars)
    }

    @Test(expected = TemplateFrequencyLookupConfigException::class)
    fun parseTemplateFrequencyLookupRule_rejectsInvalidRankType() {
        parseTemplateFrequencyLookupRules(
            """
            <script id="ankidroid-frequency-config" type="application/json">
            {"sourceField":"Front","rankType":"invalid"}
            </script>
            """.trimIndent(),
        )
    }

    @Test
    fun sanitizeRankingLookupTerm_removesNonLettersAcrossScripts() {
        assertEquals("\u5b66\u4e60abc", sanitizeRankingLookupTerm(" \u5b66.\u4e60 abc-123 ", removeNonAlphabeticChars = true))
    }

    @Test
    fun wordRankingLookup_rankForRespectsSelectedRankType() {
        val lookup =
            WordRankingLookup(
                term = "\u4e00",
                preferredRank = 12L,
                charRank = 12L,
                globalRank = 19L,
            )

        assertEquals(12L, lookup.rankFor(FREQUENCY_RANK_TYPE_AUTO))
        assertEquals(12L, lookup.rankFor(FREQUENCY_RANK_TYPE_CHAR))
        assertEquals(19L, lookup.rankFor(FREQUENCY_RANK_TYPE_GLOBAL))
    }

    @Test
    fun selectTemplateFrequencyLookupRule_prefersMatchingDeckWithinFieldSpecificRules() {
        val rules =
            listOf(
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = "Deck A",
                    sourceField = "Front",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_AUTO,
                    removeNonAlphabeticChars = false,
                ),
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = "Deck B",
                    sourceField = "Back",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_GLOBAL,
                    removeNonAlphabeticChars = false,
                ),
            )

        val selected = selectTemplateFrequencyLookupRule(rules, "HSK Frequency", "Deck B")

        assertEquals("Back", selected?.sourceField)
    }

    @Test
    fun selectTemplateFrequencyLookupRule_usesFirstDeckAgnosticRuleAsWildcard() {
        val rules =
            listOf(
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = null,
                    sourceField = "Front",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_AUTO,
                    removeNonAlphabeticChars = false,
                ),
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = "Deck B",
                    sourceField = "Back",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_GLOBAL,
                    removeNonAlphabeticChars = false,
                ),
            )

        val selected = selectTemplateFrequencyLookupRule(rules, "HSK Frequency", "Deck B")

        assertEquals("Front", selected?.sourceField)
    }

    @Test
    fun selectTemplateFrequencyLookupRule_returnsNullWhenDeckSpecificRulesDoNotMatch() {
        val rules =
            listOf(
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = "Deck A",
                    sourceField = "Front",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_AUTO,
                    removeNonAlphabeticChars = false,
                ),
                TemplateFrequencyLookupRule(
                    field = "HSK Frequency",
                    deck = "Deck C",
                    sourceField = "Back",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_GLOBAL,
                    removeNonAlphabeticChars = false,
                ),
            )

        val selected = selectTemplateFrequencyLookupRule(rules, "HSK Frequency", "Deck B")

        assertEquals(null, selected)
    }

    @Test
    fun selectTemplateFrequencyLookupRule_fallsBackToGenericFieldRules() {
        val rules =
            listOf(
                TemplateFrequencyLookupRule(
                    field = null,
                    deck = "Deck B",
                    sourceField = "Front",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_AUTO,
                    removeNonAlphabeticChars = false,
                ),
            )

        val selected = selectTemplateFrequencyLookupRule(rules, "HSK Frequency", "Deck B")

        assertEquals("Front", selected?.sourceField)
    }

    @Test
    fun hasTemplateFrequencyLookupRuleForField_detectsExactAndGenericRules() {
        val rules =
            listOf(
                TemplateFrequencyLookupRule(
                    field = null,
                    deck = "Deck B",
                    sourceField = "Front",
                    targetField = null,
                    format = FREQUENCY_FORMAT_RANK_ONLY,
                    rankType = FREQUENCY_RANK_TYPE_AUTO,
                    removeNonAlphabeticChars = false,
                ),
            )

        assertTrue(hasTemplateFrequencyLookupRuleForField(rules, "HSK Frequency"))
    }

    private fun autoRule() =
        TemplateFrequencyLookupRule(
            field = "HSK Frequency",
            deck = null,
            sourceField = "Front",
            targetField = "HSK Frequency",
            format = FREQUENCY_FORMAT_RANK_AND_TERM,
            rankType = FREQUENCY_RANK_TYPE_AUTO,
            removeNonAlphabeticChars = true,
        )

    private val chang = WordRankingLookup(term = "长", preferredRank = 71, charRank = 71, globalRank = 211)

    @Test
    fun autoRank_characterUsesCharRankWithoutDot() {
        val rank = autoRule().rankFor(chang, "长")!!
        assertEquals(71L, rank)
        assertEquals("00071 长", autoRule().formatFrequency(rank, chang, "长"))
    }

    @Test
    fun autoRank_dottedSingleCharacterWordUsesGlobalRankAndKeepsDot() {
        val rank = autoRule().rankFor(chang, "长.")!!
        assertEquals(211L, rank)
        assertEquals("00211 长.", autoRule().formatFrequency(rank, chang, "长."))
    }

    @Test
    fun autoRank_multiCharacterWordUsesGlobalRank() {
        val renshi = WordRankingLookup(term = "认识", preferredRank = 77, charRank = null, globalRank = 77)
        val rank = autoRule().rankFor(renshi, "认识")!!
        assertEquals("00077 认识", autoRule().formatFrequency(rank, renshi, "认识"))
    }

    @Test
    fun isSingleCharacterWordEntry_onlyForOneLetterWithDot() {
        assertTrue(isSingleCharacterWordEntry(" 的. "))
        assertFalse(isSingleCharacterWordEntry("的"))
        assertFalse(isSingleCharacterWordEntry("认识."))
    }
}
