package com.rjnsdev.linklift.app.util

import com.rjnsdev.linklift.app.MediaFormat
import com.rjnsdev.linklift.app.MediaKind
import com.rjnsdev.linklift.app.extractAllUrls
import com.rjnsdev.linklift.app.extractFirstUrl
import com.rjnsdev.linklift.app.isImgurUrl
import com.rjnsdev.linklift.app.isSoundCloudUrl
import com.rjnsdev.linklift.app.isYouTubeUrl
import com.rjnsdev.linklift.app.isYtDlpSupportedHost
import com.rjnsdev.linklift.app.looksLikePlaylistUrl
import com.rjnsdev.linklift.app.shouldDownloadWithMergeService
import com.rjnsdev.linklift.app.shouldRefreshUrlBeforeDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extensive test cases for supported sources (YouTube, Instagram, TikTok, Twitter/X,
 * Facebook, Reddit, Pinterest, Vimeo, SoundCloud, Imgur, Twitch, Streamable,
 * Dailymotion, Rumble, Google Drive, adult sites, and generic media links).
 */
class SupportedSourcesUrlTest {

    // =========================================================================
    // 1. YouTube Tests
    // =========================================================================

    @Test
    fun testYouTubeHostRecognition() {
        val validYouTubeUrls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/abcdef12345"
        )
        for (url in validYouTubeUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for $url", isYtDlpSupportedHost(url))
            assertTrue("Expected isYouTubeUrl=true for $url", isYouTubeUrl(url))
        }
    }

    @Test
    fun testYouTubePlaylistAndChannelDetection() {
        val playlistUrls = listOf(
            "https://www.youtube.com/playlist?list=PL1234567890ABCDEF",
            "https://music.youtube.com/playlist?list=PL1234567890ABCDEF",
            "https://www.youtube.com/channel/UC1234567890",
            "https://www.youtube.com/c/CreatorName",
            "https://www.youtube.com/user/Username",
            "https://www.youtube.com/@ChannelHandle",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890ABCDEF",
            "https://youtu.be/dQw4w9WgXcQ?list=PL1234567890ABCDEF"
        )
        for (url in playlistUrls) {
            assertTrue("Expected looksLikePlaylistUrl=true for $url", looksLikePlaylistUrl(url))
        }

        val singleVideoUrls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/abcdef12345"
        )
        for (url in singleVideoUrls) {
            assertFalse("Expected looksLikePlaylistUrl=false for $url", looksLikePlaylistUrl(url))
        }
    }

    // =========================================================================
    // 2. Instagram Tests
    // =========================================================================

    @Test
    fun testInstagramHostAndRefreshRules() {
        val instagramUrls = listOf(
            "https://www.instagram.com/p/C123456789/",
            "https://instagram.com/reel/D987654321/",
            "https://m.instagram.com/reels/E1122334455/",
            "https://instagr.am/p/F99887766/"
        )
        for (url in instagramUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for $url", isYtDlpSupportedHost(url))
            assertTrue("Instagram link must trigger URL refresh before download", shouldRefreshUrlBeforeDownload(url))
            assertFalse("Instagram post is single item, not playlist", looksLikePlaylistUrl(url))
        }
    }

    // =========================================================================
    // 3. TikTok Tests
    // =========================================================================

    @Test
    fun testTikTokHostRefreshAndMergeRules() {
        val singleTikTokUrls = listOf(
            "https://www.tiktok.com/@creator/video/1234567890123456789",
            "https://vt.tiktok.com/ZS1234567/",
            "https://m.tiktok.com/v/1234567890.html"
        )
        val dummyFormat = MediaFormat(
            formatId = "default",
            label = "720p",
            mediaUrl = "https://cdn.tiktok.com/video.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 720,
            width = 1280,
            fps = 30,
            abr = null,
            tbr = null,
            fileSizeBytes = 1048576,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = true
        )

        for (url in singleTikTokUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for TikTok $url", isYtDlpSupportedHost(url))
            assertTrue("TikTok link must trigger URL refresh before download", shouldRefreshUrlBeforeDownload(url))
            assertTrue("TikTok should use merge service router", shouldDownloadWithMergeService(dummyFormat, url))
            assertFalse("TikTok video link is not playlist", looksLikePlaylistUrl(url))
        }
    }

    @Test
    fun testTikTokProfileAndFeedPlaylistDetection() {
        val tikTokPlaylists = listOf(
            "https://www.tiktok.com/@creator",
            "https://www.tiktok.com/@creator/foryou",
            "https://www.tiktok.com/@creator/trending"
        )
        for (url in tikTokPlaylists) {
            assertTrue("TikTok profile feed expected looksLikePlaylistUrl=true for $url", looksLikePlaylistUrl(url))
        }
    }

    // =========================================================================
    // 4. Twitter / X Tests
    // =========================================================================

    @Test
    fun testTwitterXHostAndPlaylistDetection() {
        val tweetUrls = listOf(
            "https://x.com/user/status/1234567890",
            "https://twitter.com/user/status/1234567890",
            "https://mobile.twitter.com/user/status/1234567890"
        )
        for (url in tweetUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for X/Twitter $url", isYtDlpSupportedHost(url))
            assertFalse("Tweet status is single post", looksLikePlaylistUrl(url))
        }

        val xUserFeed = "https://x.com/user"
        assertTrue("X user profile expected looksLikePlaylistUrl=true", looksLikePlaylistUrl(xUserFeed))
    }

    // =========================================================================
    // 5. Facebook Tests
    // =========================================================================

    @Test
    fun testFacebookHostRefreshAndMergeRules() {
        val fbUrls = listOf(
            "https://www.facebook.com/watch/?v=123456789",
            "https://fb.watch/ab12cd34ef/",
            "https://m.facebook.com/story.php?story_fbid=100&id=200"
        )
        val dummyFormat = MediaFormat(
            formatId = "default",
            label = "1080p",
            mediaUrl = "https://video.fbbb1-1.fna.fbcdn.net/v.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 1080,
            width = 1920,
            fps = 30,
            abr = null,
            tbr = null,
            fileSizeBytes = 2097152,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = true
        )

        for (url in fbUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for FB $url", isYtDlpSupportedHost(url))
            assertTrue("Facebook links expire quickly; expected refresh=true", shouldRefreshUrlBeforeDownload(url))
            assertTrue("Facebook downloads require merge service router", shouldDownloadWithMergeService(dummyFormat, url))
        }
    }

    // =========================================================================
    // 6. Reddit & Pinterest Tests
    // =========================================================================

    @Test
    fun testRedditAndPinterestHostRecognition() {
        val redditUrls = listOf(
            "https://www.reddit.com/r/videos/comments/abc123/sample_video/",
            "https://redd.it/abc123",
            "https://old.reddit.com/r/memes/comments/xyz789/sample/"
        )
        for (url in redditUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for Reddit $url", isYtDlpSupportedHost(url))
            assertFalse("Standard Reddit post is not playlist", looksLikePlaylistUrl(url))
        }

        val pinterestUrls = listOf(
            "https://www.pinterest.com/pin/123456789012345678/",
            "https://pin.it/7a8b9c0d",
            "https://pinterest.co.uk/pin/987654321/"
        )
        for (url in pinterestUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for Pinterest $url", isYtDlpSupportedHost(url))
        }
    }

    // =========================================================================
    // 7. Vimeo, SoundCloud, Imgur, Twitch, Streamable, Dailymotion, Rumble, Google Drive
    // =========================================================================

    @Test
    fun testOtherSupportedHosts() {
        val otherHosts = mapOf(
            "Vimeo" to "https://vimeo.com/123456789",
            "SoundCloud" to "https://soundcloud.com/artist/track-title",
            "Imgur" to "https://imgur.com/gallery/a1b2c3d",
            "Twitch" to "https://clips.twitch.tv/SampleClipId",
            "Streamable" to "https://streamable.com/xyz123",
            "Dailymotion" to "https://www.dailymotion.com/video/x8sample",
            "Rumble" to "https://rumble.com/v12345-sample-video.html",
            "Google Drive" to "https://drive.google.com/file/d/1234567890abcdef/view"
        )

        for ((platform, url) in otherHosts) {
            assertTrue("Expected isYtDlpSupportedHost=true for $platform ($url)", isYtDlpSupportedHost(url))
        }

        assertTrue("SoundCloud URL matcher check", isSoundCloudUrl("https://soundcloud.com/artist/track"))
        assertTrue("Imgur URL matcher check", isImgurUrl("https://imgur.com/gallery/abc"))
    }

    @Test
    fun testOtherPlatformsPlaylistDetection() {
        val playlists = listOf(
            "https://vimeo.com/channels/staffpicks" to "Vimeo channel",
            "https://vimeo.com/showcase/123456" to "Vimeo showcase",
            "https://soundcloud.com/artist/sets/album-name" to "SoundCloud set",
            "https://www.twitch.tv/streamer/videos" to "Twitch videos feed",
            "https://imgur.com/a/albumId" to "Imgur album",
            "https://imgur.com/gallery/galleryId" to "Imgur gallery",
            "https://drive.google.com/folders/folderId123" to "Google Drive folder",
            "https://www.dailymotion.com/playlist/x7890" to "Dailymotion playlist",
            "https://rumble.com/playlists/channelName" to "Rumble playlist"
        )

        for ((url, label) in playlists) {
            assertTrue("Expected looksLikePlaylistUrl=true for $label ($url)", looksLikePlaylistUrl(url))
        }
    }

    // =========================================================================
    // 8. Adult Content Sites
    // =========================================================================

    @Test
    fun testAdultContentHostsAndRefreshRules() {
        val adultUrls = listOf(
            "https://www.xhamster.com/videos/sample-video-12345",
            "https://xhamster2.com/videos/sample-video-67890",
            "https://www.pornhub.com/view_video.php?viewkey=ph1234567"
        )
        val dummyFormat = MediaFormat(
            formatId = "default",
            label = "480p",
            mediaUrl = "https://cdn.adult.com/video.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 480,
            width = 854,
            fps = 30,
            abr = null,
            tbr = null,
            fileSizeBytes = 5000000,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = true
        )

        for (url in adultUrls) {
            assertTrue("Expected isYtDlpSupportedHost=true for adult site $url", isYtDlpSupportedHost(url))
            assertTrue("Adult links expire quickly; expected refresh=true", shouldRefreshUrlBeforeDownload(url))
            assertTrue("Adult links must use merge service router", shouldDownloadWithMergeService(dummyFormat, url))
        }
    }

    // =========================================================================
    // 9. Unsupported Hosts & Non-media Links
    // =========================================================================

    @Test
    fun testUnsupportedHosts() {
        val unsupportedUrls = listOf(
            "https://example.com/index.html",
            "https://github.com/torvalds/linux",
            "https://wikipedia.org/wiki/Kotlin",
            "https://stackoverflow.com/questions/12345"
        )
        for (url in unsupportedUrls) {
            assertFalse("Expected isYtDlpSupportedHost=false for $url", isYtDlpSupportedHost(url))
            assertFalse("Expected looksLikePlaylistUrl=false for $url", looksLikePlaylistUrl(url))
            assertFalse("Expected shouldRefreshUrlBeforeDownload=false for $url", shouldRefreshUrlBeforeDownload(url))
        }
    }

    // =========================================================================
    // 10. URL Extraction Tests (extractAllUrls, extractFirstUrl)
    // =========================================================================

    @Test
    fun testUrlExtractionFromText() {
        val sampleText = """
            Check out this cool video https://www.youtube.com/watch?v=dQw4w9WgXcQ!
            Also look at this post: https://www.instagram.com/p/C123456789/, and http://example.com.
        """.trimIndent()

        val extracted = extractAllUrls(sampleText)
        assertEquals(3, extracted.size)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", extracted[0])
        assertEquals("https://www.instagram.com/p/C123456789/", extracted[1])
        assertEquals("http://example.com", extracted[2])

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", extractFirstUrl(sampleText))
        assertNull(extractFirstUrl("No links in this text message"))
    }

    // =========================================================================
    // 11. MediaKind Determination Tests
    // =========================================================================

    @Test
    fun testMediaKindFromMimeAndExtension() {
        // Video mime types & extensions
        assertEquals(MediaKind.Video, MediaKind.fromMimeType("video/mp4", "https://cdn.com/file"))
        assertEquals(MediaKind.Video, MediaKind.fromMimeType("application/octet-stream", "https://cdn.com/file.mp4"))
        assertEquals(MediaKind.Video, MediaKind.fromMimeType("", "https://cdn.com/file.mkv"))
        assertEquals(MediaKind.Video, MediaKind.fromMimeType(null, "https://cdn.com/file.webm"))
        assertEquals(MediaKind.Video, MediaKind.fromMimeType(null, "https://cdn.com/playlist.m3u8"))

        // Audio mime types & extensions
        assertEquals(MediaKind.Audio, MediaKind.fromMimeType("audio/mpeg", "https://cdn.com/track"))
        assertEquals(MediaKind.Audio, MediaKind.fromMimeType("audio/aac", "https://cdn.com/track"))
        assertEquals(MediaKind.Audio, MediaKind.fromMimeType("", "https://cdn.com/song.mp3"))
        assertEquals(MediaKind.Audio, MediaKind.fromMimeType(null, "https://cdn.com/song.wav"))
        assertEquals(MediaKind.Audio, MediaKind.fromMimeType(null, "https://cdn.com/song.m4a"))

        // Image mime types & extensions
        assertEquals(MediaKind.Image, MediaKind.fromMimeType("image/png", "https://cdn.com/pic"))
        assertEquals(MediaKind.Image, MediaKind.fromMimeType("image/jpeg", "https://cdn.com/photo"))
        assertEquals(MediaKind.Image, MediaKind.fromMimeType("", "https://cdn.com/photo.webp"))
        assertEquals(MediaKind.Image, MediaKind.fromMimeType(null, "https://cdn.com/animation.gif"))

        // Unknown
        assertEquals(MediaKind.Unknown, MediaKind.fromMimeType("text/html", "https://example.com/page"))
    }
}
