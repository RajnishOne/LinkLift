package com.rjnsdev.linklift.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying stream format structure, MediaMuxer merge requirements,
 * and QualityPreset mapping.
 */
class MediaFormatMergeTest {

    @Test
    fun testRequiresMergeLogic() {
        val audioPair = MediaFormat(
            formatId = "140",
            label = "Audio 128kbps M4A",
            mediaUrl = "https://googlevideo.com/audio.m4a",
            mimeType = "audio/mp4",
            kind = MediaKind.Audio,
            ext = "m4a",
            height = null,
            width = null,
            fps = null,
            abr = 128,
            tbr = 128,
            fileSizeBytes = 3000000L,
            fileSizeApprox = false,
            isAudioOnly = true,
            isProgressive = false,
            isVideoOnly = false
        )

        // 1080p Video-only stream WITH audio pair -> requiresMerge = true
        val videoOnlyWithAudio = MediaFormat(
            formatId = "137",
            label = "1080p MP4",
            mediaUrl = "https://googlevideo.com/video1080p.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 1080,
            width = 1920,
            fps = 30,
            abr = null,
            tbr = 4000,
            fileSizeBytes = 25000000L,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = false,
            isVideoOnly = true,
            vcodec = "avc1",
            acodec = null,
            mergeAudio = audioPair
        )

        assertTrue("Video-only stream with paired audio must require merge", videoOnlyWithAudio.requiresMerge)

        // Progressive 720p stream (has both video & audio built-in) -> requiresMerge = false
        val progressiveVideo = MediaFormat(
            formatId = "22",
            label = "720p MP4",
            mediaUrl = "https://googlevideo.com/video720p.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 720,
            width = 1280,
            fps = 30,
            abr = 192,
            tbr = 2000,
            fileSizeBytes = 15000000L,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = true,
            isVideoOnly = false,
            vcodec = "avc1",
            acodec = "mp4a",
            mergeAudio = null
        )

        assertFalse("Progressive stream with video and audio built-in does not require merge", progressiveVideo.requiresMerge)

        // Pure audio-only format -> requiresMerge = false
        assertFalse("Audio-only format does not require merge", audioPair.requiresMerge)
    }

    @Test
    fun testShouldDownloadWithMergeServiceForVideoOnly() {
        val audioPair = MediaFormat(
            formatId = "140",
            label = "Audio 128kbps",
            mediaUrl = "https://googlevideo.com/audio.m4a",
            mimeType = "audio/mp4",
            kind = MediaKind.Audio,
            ext = "m4a",
            height = null,
            width = null,
            fps = null,
            abr = 128,
            tbr = 128,
            fileSizeBytes = 3000000L,
            fileSizeApprox = false,
            isAudioOnly = true,
            isProgressive = false,
            isVideoOnly = false
        )

        val videoOnlyFormat = MediaFormat(
            formatId = "137",
            label = "1080p MP4",
            mediaUrl = "https://googlevideo.com/video1080p.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.Video,
            ext = "mp4",
            height = 1080,
            width = 1920,
            fps = 30,
            abr = null,
            tbr = 4000,
            fileSizeBytes = 25000000L,
            fileSizeApprox = false,
            isAudioOnly = false,
            isProgressive = false,
            isVideoOnly = true,
            vcodec = "avc1",
            acodec = null,
            mergeAudio = audioPair
        )

        // Even for YouTube standard links, if requiresMerge=true, shouldDownloadWithMergeService must be true
        assertTrue(
            "Video-only format with audio pairing must route to MergeDownloadService",
            shouldDownloadWithMergeService(videoOnlyFormat, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun testQualityPresetStorageKeys() {
        assertEquals(QualityPreset.BestQuality, QualityPreset.fromStorageKey("best_quality"))
        assertEquals(QualityPreset.SmallestFile, QualityPreset.fromStorageKey("smallest_file"))
        assertEquals(QualityPreset.AskEveryTime, QualityPreset.fromStorageKey("ask_every_time"))

        // Null or unknown key defaults to BestQuality
        assertEquals(QualityPreset.BestQuality, QualityPreset.fromStorageKey(null))
        assertEquals(QualityPreset.BestQuality, QualityPreset.fromStorageKey("unknown_preset_key"))
    }
}
