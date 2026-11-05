package io.github.thegingerbehindu.rehabtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupTestLogicTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789-abc"

    @Test
    fun `reads the ID from a sheet link`() {
        assertEquals(id, parseSpreadsheetId("https://docs.google.com/spreadsheets/d/$id/edit?gid=0#gid=0"))
    }

    @Test
    fun `accepts a bare ID with surrounding spaces`() {
        assertEquals(id, parseSpreadsheetId("  $id "))
    }

    @Test
    fun `rejects anything that is not a sheet`() {
        assertNull(parseSpreadsheetId(""))
        assertNull(parseSpreadsheetId("https://example.com/not-a-sheet"))
        assertNull(parseSpreadsheetId("../../etc/passwd"))
    }

    @Test
    fun `describes how late an alarm fired`() {
        assertEquals("4s late", describeLateness(10_000, 14_000))
        assertEquals("2m 5s late", describeLateness(0, 125_000))
    }
}
