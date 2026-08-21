package com.rjnsdev.linklift.app.util

import com.rjnsdev.linklift.app.RemoteConfigHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigHelperTest {

    @Test
    fun testParseJsonUpdatesFlags() {
        val json = """
            {
              "is_youtube_available": false,
              "is_soundcloud_available": true,
              "is_imgur_available": false
            }
        """.trimIndent()

        RemoteConfigHelper.parseJson(json)

        assertFalse(RemoteConfigHelper.isYouTubeAvailable)
        assertTrue(RemoteConfigHelper.isSoundCloudAvailable)
        assertFalse(RemoteConfigHelper.isImgurAvailable)

        assertEquals("YouTube downloads are not supported", RemoteConfigHelper.getDisabledPlatformMessage("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(RemoteConfigHelper.getDisabledPlatformMessage("https://soundcloud.com/artist/track"))
        assertEquals("Imgur downloads are not supported", RemoteConfigHelper.getDisabledPlatformMessage("https://imgur.com/gallery/12345"))

        // Reset to true
        val resetJson = """
            {
              "is_youtube_available": true,
              "is_soundcloud_available": true,
              "is_imgur_available": true
            }
        """.trimIndent()
        RemoteConfigHelper.parseJson(resetJson)

        assertTrue(RemoteConfigHelper.isYouTubeAvailable)
        assertTrue(RemoteConfigHelper.isSoundCloudAvailable)
        assertTrue(RemoteConfigHelper.isImgurAvailable)
        assertNull(RemoteConfigHelper.getDisabledPlatformMessage("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
