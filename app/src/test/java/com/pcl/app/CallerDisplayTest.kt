package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerDisplayTest {
    @Test fun showContactNameWhenKnown() {
        assertEquals("Alice", CallerDisplay.title("Alice", "+230 5555 1234"))
        assertEquals("Alice (+230 5555 1234)", CallerDisplay.callDisplay("Alice", "+230 5555 1234"))
    }

    @Test fun useNumberWhenSavedNameIsGeneric() {
        assertEquals("+230 5555 1234", CallerDisplay.title(" UNKNOWN ", "+230 5555 1234"))
        assertEquals("55551234", CallerDisplay.title("Unknown number", "55551234"))
        assertEquals("55551234", CallerDisplay.title("No Caller ID", "55551234"))
    }

    @Test fun showDashIfNeitherIdentifierIsAvailable() {
        assertEquals("-", CallerDisplay.title("", ""))
        assertEquals("-", CallerDisplay.title("unknown", ""))
        assertEquals("-", CallerDisplay.title("private number", "unknown number"))
        assertEquals("-", CallerDisplay.callDisplay("Unknown", "Withheld"))
    }

    @Test fun avoidInvalidNumbersInCallActions() {
        assertEquals("", CallerDisplay.number("  UNKNOWN  "))
        assertEquals("", CallerDisplay.number("-"))
        assertEquals("+23012345678", CallerDisplay.number(" +23012345678 "))
    }
}
