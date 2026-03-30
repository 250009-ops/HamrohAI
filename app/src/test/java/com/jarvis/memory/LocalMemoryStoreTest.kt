package com.jarvis.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMemoryStoreTest {

    @Test
    fun `ranks summaries by query frequency`() {
        val data = listOf(
            SessionSummary(id = 1, summary = "Bugun budilnik va eslatma", createdAt = 1),
            SessionSummary(id = 2, summary = "Faqat budilnik", createdAt = 2),
            SessionSummary(id = 3, summary = "Hech narsa", createdAt = 3)
        )

        val ranked = LocalMemoryStore.rankSummariesByKeyword("budilnik", data)
        assertEquals(1L, ranked.first().id)
    }
}
