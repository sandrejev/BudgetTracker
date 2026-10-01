package com.example.budgettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [ShopNameMatcher]. Run with:  ./gradlew test */
class ShopNameMatcherTest {

    private val shops = listOf("ALDI", "dm", "LIDL", "Müller", "Penny", "REWE", "Rossmann")

    @Test
    fun `OCR noise matches the known shop`() {
        assertEquals("Müller", ShopNameMatcher.bestMatch(listOf("3Müller"), shops))
        assertEquals("LIDL", ShopNameMatcher.bestMatch(listOf("LGDL"), shops))
        assertEquals("REWE", ShopNameMatcher.bestMatch(listOf("R E W E"), shops))
        assertEquals("Müller", ShopNameMatcher.bestMatch(listOf("Mueller"), shops))
    }

    @Test
    fun `shop name inside a longer header line matches`() {
        assertEquals("LIDL", ShopNameMatcher.bestMatch(listOf("LIDL Dienstleistung GmbH"), shops))
        assertEquals("Müller", ShopNameMatcher.bestMatch(listOf("3Müller Drogerie Markt"), shops))
        assertEquals("Rossmann", ShopNameMatcher.bestMatch(listOf("Kassenbon", "ROSSMAN GmbH"), shops))
    }

    @Test
    fun `unrelated text does not match`() {
        assertNull(ShopNameMatcher.bestMatch(listOf("Kassenbon"), shops))
        assertNull(ShopNameMatcher.bestMatch(listOf("EDEKA"), shops))
        assertNull(ShopNameMatcher.bestMatch(listOf(""), shops))
    }

    @Test
    fun `short names only match exactly`() {
        assertEquals("dm", ShopNameMatcher.bestMatch(listOf("DM"), shops))
        assertNull(ShopNameMatcher.bestMatch(listOf("dn"), shops))
    }

    @Test
    fun `suggestions rank prefix before fuzzy and return all for blank query`() {
        assertEquals(shops.take(6), ShopNameMatcher.suggestions("", shops))
        assertEquals("REWE", ShopNameMatcher.suggestions("re", shops).first())
        assertTrue("LIDL" in ShopNameMatcher.suggestions("LGDL", shops))
        assertEquals("Müller", ShopNameMatcher.suggestions("mul", shops).first())
    }
}
