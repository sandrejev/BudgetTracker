package com.example.budgettracker.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [CommonNamePrompt]. Run with:  ./gradlew test */
class CommonNamePromptTest {

    private val categories = listOf("Fresh fruits", "Dairy & eggs", "Other")

    @Test
    fun `parses name and category objects`() {
        val json = """{"1": {"name": "whole milk", "category": "Dairy & eggs"},
                       "2": {"name": " plums ", "category": "fresh FRUITS"}}"""
        assertEquals(
            mapOf(1 to NameSuggestion("whole milk", "Dairy & eggs"), 2 to NameSuggestion("plums", "Fresh fruits")),
            CommonNamePrompt.parse(json, 2, categories)
        )
    }

    @Test
    fun `unknown category is dropped, name kept`() {
        val json = """{"1": {"name": "coffee beans", "category": "Hot drinks"}}"""
        assertEquals(mapOf(1 to NameSuggestion("coffee beans", null)), CommonNamePrompt.parse(json, 1, categories))
    }

    @Test
    fun `accepts old string format and skips missing or blank entries`() {
        val json = """{"1": "whole milk", "2": "", "4": {"name": "plums"}}"""
        assertEquals(
            mapOf(1 to NameSuggestion("whole milk"), 4 to NameSuggestion("plums")),
            CommonNamePrompt.parse(json, 4, categories)
        )
    }

    @Test
    fun `prompt lists items, known names and categories`() {
        val prompt = CommonNamePrompt.build(listOf("BIO VOLLM.", "PFLAUMEN"), listOf("whole milk"), categories)
        assertTrue("1. BIO VOLLM." in prompt && "2. PFLAUMEN" in prompt)
        assertTrue("Known common names: whole milk" in prompt)
        assertTrue("Categories: Fresh fruits, Dairy & eggs, Other" in prompt)
    }
}
