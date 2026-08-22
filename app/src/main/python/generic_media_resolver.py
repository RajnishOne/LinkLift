import json
import os
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError
import yt_dlp.version as _ytdlp_ver

# yt-dlp 2026.8.17+ deprecated android_vr (all HTTPS formats 403'd).
# From that version, the extractor's own defaults are 'visionos' + 'web'.
# For older builds (e.g. 2025.10.14 on Mac), android_vr still works well.
_YTDLP_VERSION = tuple(
    int(x) for x in _ytdlp_ver.__version__.split(".")
    if x.isdigit()
)
_YTDLP_ANDROID_VR_BROKEN = _YTDLP_VERSION >= (2026, 8, 17)

if _YTDLP_ANDROID_VR_BROKEN:
    # 2026.8.19+ defaults; visionos + web for anon, web_embedded/tv_downgraded/web for authed
    _DEFAULT_YOUTUBE_CLIENTS_ANONYMOUS = ["visionos", "web"]
    _DEFAULT_YOUTUBE_CLIENTS_AUTHENTICATED = ["web_embedded", "tv_downgraded", "web"]
else:
    # Pre-2026.8.17 — android_vr reliably returns full DASH format table
    _DEFAULT_YOUTUBE_CLIENTS_ANONYMOUS = ["android_vr", "android"]
    _DEFAULT_YOUTUBE_CLIENTS_AUTHENTICATED = ["web", "android_vr", "android"]

_YT_DLP_BASE_OPTIONS = {
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "format": "all",
    "proxy": "",
    "extractor_args": {
        "youtube": {
            "player_client": _DEFAULT_YOUTUBE_CLIENTS_ANONYMOUS,
        },
    },
}


def _ydl_options(cookie_file_path=None, **overrides):
    options = dict(_YT_DLP_BASE_OPTIONS)
    extractor_args = options.get("extractor_args")
    if isinstance(extractor_args, dict):
        options["extractor_args"] = {key: dict(value) for key, value in extractor_args.items()}

    has_cookies = bool(
        cookie_file_path
        and isinstance(cookie_file_path, str)
        and os.path.exists(cookie_file_path)
        and os.path.getsize(cookie_file_path) > 0
    )

    if has_cookies:
        options["cookiefile"] = cookie_file_path
        if "youtube" in options["extractor_args"]:
            options["extractor_args"]["youtube"]["player_client"] = _DEFAULT_YOUTUBE_CLIENTS_AUTHENTICATED

    options.update(overrides)
    return options


def _clean_yt_dlp_error(message):
    """Strip yt-dlp's noisy prefixes/cookie hints from a DownloadError message."""
    text = (message or "").strip()
    # Drop the leading "ERROR: [extractor] id:" prefix yt-dlp adds.
    if text.lower().startswith("error:"):
        text = text.split(":", 1)[1].strip()
    while text.startswith("[") and "]" in text:
        text = text.split("]", 1)[1].strip()
    if ":" in text and text.split(":", 1)[0].strip().count(" ") == 0:
        # e.g. "ufVSsPu_3xA: Sign in to confirm..." -> drop the id.
        head, tail = text.split(":", 1)
        if len(head) <= 16 and not any(ch.isspace() for ch in head):
            text = tail.strip()
    # yt-dlp often suggests cookies; that hint is verbose for end users.
    for marker in (" Use --cookies-from-browser", " See "):
        idx = text.find(marker)
        if idx > 0:
            text = text[:idx].strip()
    return text or "yt-dlp couldn't extract this link"


def _best_entry(info):
    if not info:
        return None

    if info.get("entries"):
        for entry in info["entries"]:
            if entry:
                return entry
        return None

    return info


def _entry_iter(info):
    if not info:
        return []
    entries = info.get("entries") or []
    if entries:
        return [entry for entry in entries if entry]
    return [info]


def _best_requested_download(entry):
    downloads = entry.get("requested_downloads") or []
    for item in downloads:
        url = item.get("url")
        ext = (item.get("ext") or "").lower()
        fid = str(item.get("format_id") or "")
        if url and ext not in {"mhtml", "none"} and not fid.startswith("sb"):
            return item
    return None


def _kind_from_entry(entry, ext):
    if (entry.get("vcodec") and entry.get("vcodec") != "none") or ext in {
        "mp4",
        "webm",
        "mkv",
        "mov",
        "m4v",
        "3gp",
    }:
        return "video"
    if (entry.get("acodec") and entry.get("acodec") != "none") or ext in {
        "mp3",
        "m4a",
        "aac",
        "wav",
        "ogg",
        "opus",
        "flac",
    }:
        return "audio"
    if ext in {"jpg", "jpeg", "png", "webp", "gif"}:
        return "image"
    return "video"


def _mime_from_kind(kind, ext):
    if kind == "audio":
        return f"audio/{ext or 'mpeg'}"
    if kind == "image":
        if ext == "jpg":
            ext = "jpeg"
        return f"image/{ext or 'jpeg'}"
    return f"video/{ext or 'mp4'}"


_DIRECT_HTTP_PROTOCOLS = {"http", "https"}
_MP4_FAMILY_EXTS = {"mp4", "m4v", "mov", "3gp"}
_WEBM_FAMILY_EXTS = {"webm", "mkv"}
_MP4_AUDIO_EXTS = {"m4a", "mp4", "aac"}
_WEBM_AUDIO_EXTS = {"webm", "opus", "ogg"}
# MediaMuxer supports these video codecs into MP4/WebM containers.
_MUXER_VIDEO_CODECS = {"avc", "h264", "hev", "h265", "hvc", "vp8", "vp08", "vp9", "vp09", "av01", "av1", "mp4v"}
_MUXER_AUDIO_CODECS = {"aac", "mp4a", "opus", "vorbis", "mp3", "ogg", "flac", "wav"}


def _format_label(fmt, kind, ext, height, fps, abr):
    if kind == "audio":
        parts = ["Audio"]
        if abr:
            parts.append(f"{int(round(abr))} kbps")
        parts.append((ext or "m4a").upper())
        return " ".join(parts)

    parts = []
    if height:
        parts.append(f"{int(height)}p")
    if fps and fps > 30:
        parts.append(f"{int(round(fps))}fps")
    if ext:
        parts.append(ext.upper())
    return " ".join(parts) if parts else "Video"


def _codec_family(codec):
    if not codec:
        return ""
    codec = codec.lower()
    # Trim parameter strings like "avc1.640028"
    return codec.split(".", 1)[0]


def _is_muxer_compatible_video(vcodec):
    family = _codec_family(vcodec)
    return any(family.startswith(prefix) for prefix in _MUXER_VIDEO_CODECS)


def _is_muxer_compatible_audio(acodec):
    family = _codec_family(acodec)
    return any(family.startswith(prefix) for prefix in _MUXER_AUDIO_CODECS)


def _format_payload(fmt):
    if not fmt:
        return None

    url = fmt.get("url")
    if not url:
        return None

    protocol = (fmt.get("protocol") or "").lower()
    if protocol and protocol not in _DIRECT_HTTP_PROTOCOLS:
        # Skip segmented (DASH / HLS) and RTMP — we can't fetch those with a single GET.
        return None

    ext = (fmt.get("ext") or "").lower()
    fid = str(fmt.get("format_id") or "")
    if ext in {"mhtml", "none"} or fid.startswith("sb"):
        return None

    vcodec = (fmt.get("vcodec") or "none").lower()
    acodec = (fmt.get("acodec") or "none").lower()
    has_video = vcodec != "none"
    has_audio = acodec != "none"
    if not has_video and not has_audio:
        return None

    is_audio_only = has_audio and not has_video
    is_video_only = has_video and not has_audio
    is_progressive = has_video and has_audio
    kind = "audio" if is_audio_only else "video"

    # Drop streams in codecs Android's MediaMuxer can't passthrough — we'd be
    # downloading something we can't combine. Progressive and pure audio are kept.
    if is_video_only and not _is_muxer_compatible_video(vcodec):
        return None

    ext = (fmt.get("ext") or ("m4a" if is_audio_only else "mp4")).lower()
    height = fmt.get("height")
    width = fmt.get("width")
    fps = fmt.get("fps")
    abr = fmt.get("abr") or fmt.get("asr")
    tbr = fmt.get("tbr")
    filesize = fmt.get("filesize")
    filesize_approx = False
    if not filesize:
        filesize = fmt.get("filesize_approx")
        filesize_approx = bool(filesize)

    mime_type = _mime_from_kind(kind, ext)
    label = _format_label(fmt, kind, ext, height, fps, abr)

    http_headers = fmt.get("http_headers") or {}
    if not isinstance(http_headers, dict):
        http_headers = {}

    return {
        "format_id": str(fmt.get("format_id") or "").strip() or label,
        "label": label,
        "kind": kind,
        "media_url": url,
        "mime_type": mime_type,
        "ext": ext,
        "vcodec": _codec_family(vcodec) if has_video else None,
        "acodec": _codec_family(acodec) if has_audio else None,
        "height": int(height) if isinstance(height, (int, float)) else None,
        "width": int(width) if isinstance(width, (int, float)) else None,
        "fps": int(round(fps)) if isinstance(fps, (int, float)) else None,
        "abr": int(round(abr)) if isinstance(abr, (int, float)) else None,
        "tbr": int(round(tbr)) if isinstance(tbr, (int, float)) else None,
        "filesize": int(filesize) if isinstance(filesize, (int, float)) else None,
        "filesize_approx": filesize_approx,
        "is_audio_only": is_audio_only,
        "is_video_only": is_video_only,
        "is_progressive": is_progressive,
        "http_headers": {str(k): str(v) for k, v in http_headers.items() if v},
    }


def _strip_payload_for_pair(payload):
    """Return a copy of a format payload trimmed of self-references for embedding as merge_audio."""
    out = dict(payload)
    out.pop("merge_audio", None)
    return out


def _pick_audio_pair(video_fmt, audio_pool):
    if not audio_pool:
        return None
    video_ext = (video_fmt.get("ext") or "").lower()
    if video_ext in _MP4_FAMILY_EXTS:
        preferred = _MP4_AUDIO_EXTS
    elif video_ext in _WEBM_FAMILY_EXTS:
        preferred = _WEBM_AUDIO_EXTS
    else:
        preferred = None

    pool = audio_pool
    if preferred:
        narrowed = [a for a in audio_pool if (a.get("ext") or "").lower() in preferred]
        if narrowed:
            pool = narrowed

    return max(
        pool,
        key=lambda a: (
            (a.get("abr") or 0),
            (a.get("filesize") or 0),
        ),
        default=None,
    )


def _entry_formats(entry):
    raw_formats = entry.get("formats") or []
    enumerated = []
    for fmt in raw_formats:
        payload = _format_payload(fmt)
        if payload:
            enumerated.append(payload)

    # Split into buckets.
    progressive = []
    video_only = []
    audio_only = []
    for fmt in enumerated:
        if fmt.get("is_progressive"):
            progressive.append(fmt)
        elif fmt.get("is_video_only"):
            video_only.append(fmt)
        elif fmt.get("is_audio_only"):
            audio_only.append(fmt)

    # Dedupe progressive by (height, ext) — keep best bitrate.
    progressive_dedup = {}
    for fmt in progressive:
        key = (fmt.get("height") or 0, fmt.get("ext"))
        existing = progressive_dedup.get(key)
        if not existing or (fmt.get("tbr") or 0) > (existing.get("tbr") or 0):
            progressive_dedup[key] = fmt
    progressive_sorted = sorted(
        progressive_dedup.values(),
        key=lambda f: (-(f.get("height") or 0), -(f.get("tbr") or 0)),
    )

    # Dedupe video-only by (height, ext) — keep best bitrate.
    video_only_dedup = {}
    for fmt in video_only:
        key = (fmt.get("height") or 0, fmt.get("ext"))
        existing = video_only_dedup.get(key)
        if not existing or (fmt.get("tbr") or 0) > (existing.get("tbr") or 0):
            video_only_dedup[key] = fmt
    video_only_sorted = sorted(
        video_only_dedup.values(),
        key=lambda f: (-(f.get("height") or 0), -(f.get("tbr") or 0)),
    )

    # Pair each video-only with the best matching audio-only.
    for v_fmt in video_only_sorted:
        pair = _pick_audio_pair(v_fmt, audio_only)
        if not pair:
            v_fmt["merge_audio"] = None
            continue
        v_fmt["merge_audio"] = _strip_payload_for_pair(pair)
        # Combined filesize is video + audio when both known.
        v_size = v_fmt.get("filesize")
        a_size = pair.get("filesize")
        if v_size and a_size:
            v_fmt["filesize"] = int(v_size) + int(a_size)
            v_fmt["filesize_approx"] = v_fmt.get("filesize_approx") or pair.get("filesize_approx", False)

    # Drop video-only formats that couldn't be paired (we can't deliver a playable file).
    video_only_paired = [v for v in video_only_sorted if v.get("merge_audio")]

    # Dedupe audio-only per ext, prefer m4a/mp4 then webm.
    audio_by_ext = {}
    for fmt in audio_only:
        ext_key = fmt.get("ext")
        existing = audio_by_ext.get(ext_key)
        if not existing or (fmt.get("abr") or 0) > (existing.get("abr") or 0):
            audio_by_ext[ext_key] = fmt
    audio_priority = {"m4a": 0, "mp4": 1, "aac": 2, "mp3": 3, "ogg": 4, "opus": 5, "webm": 6}
    audio_formats = sorted(
        audio_by_ext.values(),
        key=lambda f: (audio_priority.get(f.get("ext"), 99), -(f.get("abr") or 0)),
    )

    # Merge video options: progressive first, then a video-only entry for any
    # resolution not already covered by progressive (e.g. 1080p, 1440p).
    seen_heights = {(p.get("height") or 0, p.get("ext")) for p in progressive_sorted}
    extra_merge = []
    for v_fmt in video_only_paired:
        key = (v_fmt.get("height") or 0, v_fmt.get("ext"))
        if key not in seen_heights:
            extra_merge.append(v_fmt)
            seen_heights.add(key)

    combined_videos = progressive_sorted + extra_merge
    if not combined_videos and video_only_sorted:
        combined_videos = video_only_sorted

    combined_videos.sort(key=lambda f: (-(f.get("height") or 0), 0 if f.get("is_progressive") else 1))

    return combined_videos + audio_formats


def _fallback_format_from_entry(entry):
    """Build a single-format payload from a non-yt-dlp-format entry (direct stream)."""
    requested = _best_requested_download(entry) or {}
    media_url = requested.get("url") or entry.get("url") or entry.get("webpage_url")
    if not media_url:
        return None

    ext = (requested.get("ext") or entry.get("ext") or "mp4").lower()
    if ext in {"mhtml", "none", "sb3", "sb2", "sb1", "sb0"}:
        return None

    vcodec = (entry.get("vcodec") or "").lower()
    acodec = (entry.get("acodec") or "").lower()
    has_video = bool(vcodec) and vcodec != "none"
    has_audio = bool(acodec) and acodec != "none"
    if not has_video and not has_audio:
        # Fall back to ext-based detection.
        kind_str = _kind_from_entry(entry, ext)
        has_video = kind_str == "video"
        has_audio = kind_str in {"video", "audio"}

    if not has_video and not has_audio:
        return None

    height = entry.get("height")
    if isinstance(height, (int, float)) and height < 120:
        return None

    is_audio_only = has_audio and not has_video
    kind = "audio" if is_audio_only else "video"
    fps = entry.get("fps")
    abr = entry.get("abr") or entry.get("asr")
    label = _format_label({}, kind, ext, height, fps, abr) or "Original"

    return {
        "format_id": "default",
        "label": label or "Original",
        "kind": kind,
        "media_url": media_url,
        "mime_type": _mime_from_kind(kind, ext),
        "ext": ext,
        "vcodec": None,
        "acodec": None,
        "height": int(height) if isinstance(height, (int, float)) else None,
        "width": int(entry.get("width")) if isinstance(entry.get("width"), (int, float)) else None,
        "fps": int(round(fps)) if isinstance(fps, (int, float)) else None,
        "abr": int(round(abr)) if isinstance(abr, (int, float)) else None,
        "tbr": int(round(entry.get("tbr"))) if isinstance(entry.get("tbr"), (int, float)) else None,
        "filesize": int(entry.get("filesize")) if isinstance(entry.get("filesize"), (int, float)) else None,
        "filesize_approx": False,
        "is_audio_only": is_audio_only,
        "is_video_only": False,
        "is_progressive": True,
        "http_headers": {},
    }


def _entry_payload(entry, fallback_title=None, fallback_description=None):
    formats = _entry_formats(entry)
    if not formats:
        # Check raw formats directly before falling back
        for fmt in entry.get("formats") or []:
            url = fmt.get("url")
            ext = (fmt.get("ext") or "").lower()
            fid = str(fmt.get("format_id") or "")
            if url and ext not in {"mhtml", "none"} and not fid.startswith("sb"):
                payload = _format_payload(fmt)
                if payload:
                    formats.append(payload)

    if not formats:
        fallback = _fallback_format_from_entry(entry)
        if fallback:
            formats = [fallback]

    if not formats:
        return None

    primary = formats[0]
    extractor = entry.get("extractor_key") or entry.get("extractor") or "Media"
    title = (entry.get("title") or fallback_title or extractor).strip()
    description = (
        entry.get("description")
        or fallback_description
        or f"Resolved with yt-dlp from {extractor}"
    )

    duration = entry.get("duration")
    duration_ms = None
    if isinstance(duration, (int, float)) and duration > 0:
        duration_ms = int(duration * 1000)

    return {
        "kind": primary["kind"],
        "media_url": primary["media_url"],
        "mime_type": primary["mime_type"],
        "title": title[:140],
        "description": description,
        "platform": extractor,
        "duration_ms": duration_ms,
        "formats": formats,
    }


def resolve_url(url, cookie_file_path=None):
    url = (url or "").strip()
    if not url:
        raise ValueError("Empty media URL")

    options = _ydl_options(
        cookie_file_path=cookie_file_path,
        noplaylist=True,
        extract_flat=False,
    )

    info = None
    last_error = None

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
    except DownloadError as error:
        last_error = error

    if not info and "youtube" in url.lower():
        # Fallback: try the secondary client list
        _fallback_clients = (
            ["web", "tv_simply"] if _YTDLP_ANDROID_VR_BROKEN
            else ["android_vr", "web"]
        )
        fallback_options = dict(options)
        fallback_options["extractor_args"] = {
            "youtube": {
                "player_client": _fallback_clients,
            }
        }
        try:
            with YoutubeDL(fallback_options) as ydl:
                info = ydl.extract_info(url, download=False)
        except DownloadError as fallback_err:
            last_error = fallback_err

    if not info and last_error:
        raise ValueError(_clean_yt_dlp_error(str(last_error)))

    entry = _best_entry(info)
    if not entry:
        raise ValueError("No media entries found")

    extractor = entry.get("extractor_key") or entry.get("extractor") or "Media"
    fallback_title = (entry.get("title") or extractor).strip()
    fallback_description = f"Resolved with yt-dlp from {extractor}"
    items = []
    for child in _entry_iter(info):
        payload = _entry_payload(
            child,
            fallback_title=fallback_title,
            fallback_description=fallback_description,
        )
        if payload:
            items.append(payload)

    if not items:
        raise ValueError("No downloadable media URL found")

    primary = dict(items[0])
    primary["title"] = fallback_title[:140]
    primary["description"] = fallback_description
    primary["platform"] = extractor
    primary["items"] = items
    return json.dumps(primary)


def _entry_webpage_url(entry):
    candidates = (
        entry.get("webpage_url"),
        entry.get("original_url"),
        entry.get("url"),
    )
    for candidate in candidates:
        if candidate and isinstance(candidate, str) and candidate.strip():
            return candidate.strip()

    entry_id = entry.get("id")
    extractor = (entry.get("ie_key") or entry.get("extractor_key") or "").lower()
    if entry_id and ("youtube" in extractor or extractor == "youtubetab"):
        return f"https://www.youtube.com/watch?v={entry_id}"
    return None


def _flat_entry_payload(entry):
    source_url = _entry_webpage_url(entry)
    if not source_url:
        return None

    duration = entry.get("duration")
    duration_ms = None
    if isinstance(duration, (int, float)) and duration > 0:
        duration_ms = int(duration * 1000)

    title = (entry.get("title") or "").strip() or "Untitled"
    uploader = (
        entry.get("uploader")
        or entry.get("channel")
        or entry.get("creator")
        or ""
    ).strip()

    return {
        "title": title[:140],
        "source_url": source_url,
        "duration_ms": duration_ms,
        "uploader": uploader[:80],
    }


def resolve_playlist(url, cookie_file_path=None, max_items=200):
    """Expand a playlist, channel, or user feed URL into a flat list of entries.

    Uses yt-dlp's flat extraction so we only fetch metadata for each entry without
    resolving full media URLs. The caller is expected to resolve each selected
    entry individually before downloading.

    Returns a JSON string. When the URL is not a playlist, returns a payload with
    ``is_playlist`` = false and an empty ``entries`` list so the caller can fall
    back to the single-item resolver.
    """
    url = (url or "").strip()
    if not url:
        raise ValueError("Empty media URL")

    try:
        max_items_int = max(1, int(max_items))
    except (TypeError, ValueError):
        max_items_int = 200

    options = _ydl_options(
        cookie_file_path=cookie_file_path,
        noplaylist=False,
        extract_flat="in_playlist",
        playlistend=max_items_int,
    )

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
    except DownloadError as error:
        raise ValueError(str(error))

    raw_entries = (info or {}).get("entries") or []
    info_type = (info or {}).get("_type", "")
    is_playlist = bool(raw_entries) and (
        info_type in {"playlist", "multi_video"} or len(raw_entries) > 1
    )

    out_entries = []
    if is_playlist:
        for entry in raw_entries:
            if not entry:
                continue
            payload = _flat_entry_payload(entry)
            if payload:
                out_entries.append(payload)
                if len(out_entries) >= max_items_int:
                    break

    extractor = (
        (info or {}).get("extractor_key")
        or (info or {}).get("extractor")
        or "Media"
    )
    total_count_raw = (info or {}).get("playlist_count")
    if not isinstance(total_count_raw, int) or total_count_raw <= 0:
        total_count_raw = len(out_entries)

    payload = {
        "is_playlist": bool(out_entries),
        "title": ((info or {}).get("title") or "Playlist").strip()[:140],
        "uploader": ((info or {}).get("uploader") or (info or {}).get("channel") or "").strip()[:80],
        "platform": extractor,
        "total_count": int(total_count_raw),
        "returned_count": len(out_entries),
        "truncated": total_count_raw > len(out_entries),
        "entries": out_entries,
    }
    return json.dumps(payload)
