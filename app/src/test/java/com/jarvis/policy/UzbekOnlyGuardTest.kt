package com.jarvis.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UzbekOnlyGuardTest {

    private val guard = UzbekOnlyGuard()

    @Test
    fun `accepts uzbek input`() {
        val result = guard.validateInput("Iltimos budilnik qo'yib bering")
        assertTrue(result.accepted)
    }

    @Test
    fun `rejects mostly english input`() {
        val result = guard.validateInput("Please set an alarm for 7")
        assertFalse(result.accepted)
    }
}
