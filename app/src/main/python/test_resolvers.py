import os
import tempfile
import unittest
from generic_media_resolver import (
    _clean_yt_dlp_error,
    _kind_from_entry,
    _mime_from_kind,
    _format_label,
    _is_muxer_compatible_video,
    _is_muxer_compatible_audio,
    _pick_audio_pair,
    _entry_formats,
    _entry_webpage_url,
    _ydl_options,
)
from instagram_resolver import (
    _build_title,
    _video_payload,
    _image_payload,
    _sidecar_node_payload,
)


class DummyObject:
    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)


class TestGenericMediaResolver(unittest.TestCase):

    def test_ydl_options_with_and_without_cookies(self):
        opts_plain = _ydl_options()
        self.assertNotIn("cookiefile", opts_plain)
        self.assertEqual(opts_plain["extractor_args"]["youtube"]["player_client"], ["android", "ios"])

        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("# Netscape HTTP Cookie File\n")
            f_name = f.name

        try:
            opts_cookie = _ydl_options(cookie_file_path=f_name)
            self.assertEqual(opts_cookie["cookiefile"], f_name)
            self.assertEqual(opts_cookie["extractor_args"]["youtube"]["player_client"], ["web", "tv_downgraded", "web_embedded", "android"])
        finally:
            if os.path.exists(f_name):
                os.remove(f_name)

    def test_clean_yt_dlp_error(self):
        err1 = "ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot. See https://..."
        cleaned1 = _clean_yt_dlp_error(err1)
        self.assertEqual(cleaned1, "Sign in to confirm you're not a bot.")

        err2 = "[youtube] Unable to extract video data"
        cleaned2 = _clean_yt_dlp_error(err2)
        self.assertEqual(cleaned2, "Unable to extract video data")

        err3 = "Video unavailable"
        cleaned3 = _clean_yt_dlp_error(err3)
        self.assertEqual(cleaned3, "Video unavailable")

    def test_kind_and_mime_from_entry(self):
        self.assertEqual(_kind_from_entry({"vcodec": "avc1"}, "mp4"), "video")
        self.assertEqual(_kind_from_entry({"acodec": "mp4a"}, "m4a"), "audio")
        self.assertEqual(_kind_from_entry({}, "jpg"), "image")

        self.assertEqual(_mime_from_kind("video", "mp4"), "video/mp4")
        self.assertEqual(_mime_from_kind("audio", "m4a"), "audio/m4a")
        self.assertEqual(_mime_from_kind("image", "jpg"), "image/jpeg")

    def test_format_label(self):
        video_label = _format_label({}, "video", "mp4", 1080, 60, None)
        self.assertEqual(video_label, "1080p 60fps MP4")

        audio_label = _format_label({}, "audio", "m4a", None, None, 128)
        self.assertEqual(audio_label, "Audio 128 kbps M4A")

    def test_muxer_codec_compatibility(self):
        # Compatible video codecs for Android MediaMuxer
        self.assertTrue(_is_muxer_compatible_video("avc1.640028"))
        self.assertTrue(_is_muxer_compatible_video("h264"))
        self.assertTrue(_is_muxer_compatible_video("hev1.1.6.L93.B0"))
        self.assertTrue(_is_muxer_compatible_video("vp9"))
        self.assertTrue(_is_muxer_compatible_video("av01.0.08M.08"))

        # Incompatible video codec
        self.assertFalse(_is_muxer_compatible_video("unknown_codec_xyz"))

        # Compatible audio codecs for MediaMuxer and audio downloads
        self.assertTrue(_is_muxer_compatible_audio("mp4a.40.2"))
        self.assertTrue(_is_muxer_compatible_audio("aac"))
        self.assertTrue(_is_muxer_compatible_audio("opus"))
        self.assertTrue(_is_muxer_compatible_audio("flac"))
        self.assertFalse(_is_muxer_compatible_audio("unknown_audio_codec"))

    def test_pick_audio_pair(self):
        video_mp4 = {"ext": "mp4"}
        audio_m4a = {
            "ext": "m4a",
            "url": "http://example.com/audio.m4a",
            "abr": 128,
            "vcodec": "none",
            "acodec": "mp4a",
        }
        audio_opus = {
            "ext": "opus",
            "url": "http://example.com/audio.opus",
            "abr": 160,
            "vcodec": "none",
            "acodec": "opus",
        }

        # MP4 video should prefer MP4 audio family (m4a/aac)
        pair = _pick_audio_pair(video_mp4, [audio_m4a, audio_opus])
        self.assertEqual(pair["ext"], "m4a")

        # WebM video should prefer WebM audio family (opus/webm)
        video_webm = {"ext": "webm"}
        pair_webm = _pick_audio_pair(video_webm, [audio_m4a, audio_opus])
        self.assertEqual(pair_webm["ext"], "opus")

    def test_entry_formats_pairing_and_deduplication(self):
        entry = {
            "formats": [
                {
                    "format_id": "137",
                    "url": "http://example.com/1080p.mp4",
                    "vcodec": "avc1.640028",
                    "acodec": "none",
                    "ext": "mp4",
                    "height": 1080,
                    "tbr": 4000,
                    "protocol": "https",
                },
                {
                    "format_id": "140",
                    "url": "http://example.com/audio.m4a",
                    "vcodec": "none",
                    "acodec": "mp4a.40.2",
                    "ext": "m4a",
                    "abr": 128,
                    "protocol": "https",
                },
                {
                    "format_id": "18",
                    "url": "http://example.com/360p.mp4",
                    "vcodec": "avc1.42001E",
                    "acodec": "mp4a.40.2",
                    "ext": "mp4",
                    "height": 360,
                    "tbr": 600,
                    "protocol": "https",
                },
            ]
        }
        formats = _entry_formats(entry)
        self.assertTrue(len(formats) >= 2)

        # 1080p video-only format should have merge_audio attached
        fmt_1080p = next(f for f in formats if f.get("height") == 1080)
        self.assertIsNotNone(fmt_1080p.get("merge_audio"))
        self.assertEqual(fmt_1080p["merge_audio"]["ext"], "m4a")

    def test_entry_webpage_url(self):
        entry_yt = {"id": "dQw4w9WgXcQ", "extractor_key": "Youtube"}
        self.assertEqual(_entry_webpage_url(entry_yt), "https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        entry_web = {"webpage_url": "https://vimeo.com/12345"}
        self.assertEqual(_entry_webpage_url(entry_web), "https://vimeo.com/12345")


class TestInstagramResolver(unittest.TestCase):

    def test_build_title(self):
        post1 = DummyObject(caption="Check out this cool photo!\nSecond line", owner_username="user1", shortcode="C123")
        self.assertEqual(_build_title(post1), "Check out this cool photo!")

        post2 = DummyObject(caption=None, owner_username="user2", shortcode="C456")
        self.assertEqual(_build_title(post2), "user2_C456")

    def test_payload_generators(self):
        post = DummyObject(
            caption="Sample caption",
            owner_username="john_doe",
            shortcode="ABC123xyz",
            video_url="http://example.com/video.mp4",
            url="http://example.com/image.jpg",
        )

        v_payload = _video_payload(post)
        self.assertEqual(v_payload["kind"], "video")
        self.assertEqual(v_payload["media_url"], "http://example.com/video.mp4")

        i_payload = _image_payload(post)
        self.assertEqual(i_payload["kind"], "image")
        self.assertEqual(i_payload["media_url"], "http://example.com/image.jpg")

    def test_sidecar_node_payload(self):
        post = DummyObject(caption="Carousel post", owner_username="jane", shortcode="SIDE1")
        video_node = DummyObject(is_video=True, video_url="http://example.com/sidecar.mp4")
        image_node = DummyObject(is_video=False, display_url="http://example.com/sidecar.jpg")

        v_res = _sidecar_node_payload(post, video_node)
        self.assertEqual(v_res["kind"], "video")
        self.assertEqual(v_res["media_url"], "http://example.com/sidecar.mp4")

        i_res = _sidecar_node_payload(post, image_node)
        self.assertEqual(i_res["kind"], "image")
        self.assertEqual(i_res["media_url"], "http://example.com/sidecar.jpg")


if __name__ == "__main__":
    unittest.main()
