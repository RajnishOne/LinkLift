package com.rjnsdev.linklift.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CookieHelperTest {

    @Test
    fun testCookieEntryToNetscapeLine() {
        val entry = CookieHelper.CookieEntry(
            domain = "youtube.com",
            path = "/",
            isSecure = true,
            expiry = 1750000000L,
            name = "LOGIN_INFO",
            value = "sample_value_123",
        )
        val line = entry.toNetscapeLine()
        assertEquals(".youtube.com\tTRUE\t/\tTRUE\t1750000000\tLOGIN_INFO\tsample_value_123", line)
    }

    @Test
    fun testFormatRawCookieStringToNetscape() {
        val raw = "LOGIN_INFO=abc123xyz; __Secure-3PSID=sec_token_999; SID=sid_val"
        val netscape = CookieHelper.formatRawCookieStringToNetscape(raw, defaultDomain = ".youtube.com")

        assertTrue(netscape.contains("# Netscape HTTP Cookie File"))
        assertTrue(netscape.contains(".youtube.com\tTRUE\t/\tTRUE\t"))
        assertTrue(netscape.contains("LOGIN_INFO\tabc123xyz"))
        assertTrue(netscape.contains("__Secure-3PSID\tsec_token_999"))
        assertTrue(netscape.contains("SID\tsid_val"))
    }
}
