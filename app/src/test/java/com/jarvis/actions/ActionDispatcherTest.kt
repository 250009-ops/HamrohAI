package com.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionDispatcherTest {

    @Test
    fun `maps reminder text to reminder action`() {
        assertEquals(ActionKind.Reminder, ActionDispatcher.detectActionKind("10 daqiqadan keyin eslatma"))
    }

    @Test
    fun `maps alarm text to alarm action`() {
        assertEquals(ActionKind.Alarm, ActionDispatcher.detectActionKind("budilnikni 07:30 ga qo'y"))
    }

    @Test
    fun `maps phone call text to call action`() {
        assertEquals(ActionKind.Call, ActionDispatcher.detectActionKind("998901112233 ga qo'ng'iroq qil"))
    }
}
