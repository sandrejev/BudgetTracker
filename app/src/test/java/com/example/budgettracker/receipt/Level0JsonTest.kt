package com.example.budgettracker.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class Level0JsonTest {

    private val doc = Level0Doc(
        "png", 1080, listOf(
            L0Section("body", listOf(
                L0Line.Tokens(listOf(
                    L0Token("Kohlrabi", 0.13425925f, 0.9512f),
                    L0Token("0,59", 0.854629635f)
                )),
                L0Line.Separator
            )),
            L0Section("vat", emptyList())
        )
    )

    @Test
    fun oneTokenPerLineWithRoundedNumbers() {
        assertEquals(
            """
            {
              "source": "png",
              "page_w": 1080,
              "sections": [
                {
                  "type": "body",
                  "lines": [
                    [
                      {"t": "Kohlrabi", "x": 0.134, "c": 0.95},
                      {"t": "0,59", "x": 0.855}
                    ],
                    {"sep": true}
                  ]
                },
                {
                  "type": "vat",
                  "lines": []
                }
              ]
            }
            """.trimIndent(),
            doc.toJsonString()
        )
    }

    @Test
    fun roundTripsAndReformatsOldJson() {
        val parsed = Level0Doc.fromJsonString(doc.toJsonString())
        assertEquals(doc.toJsonString(), parsed.toJsonString())
        val old = """{"source":"png","page_w":1080,"sections":[{"type":"body","lines":[[{"t":"Kiwi","x":0.09166666865348816}]]}]}"""
        assert("\"x\": 0.092}" in Level0Doc.reformat(old))
        assertEquals("not json", Level0Doc.reformat("not json"))
    }
}
