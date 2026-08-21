import json
import instaloader


def _build_title(post):
    caption = (post.caption or "").strip()
    if caption:
        first_line = caption.splitlines()[0].strip()
        if first_line:
            return first_line[:120]
    owner = getattr(post, "owner_username", "") or "instagram"
    return f"{owner}_{post.shortcode}"


def _video_payload(post):
    return {
        "kind": "video",
        "media_url": post.video_url,
        "mime_type": "video/mp4",
        "title": _build_title(post),
        "description": "Resolved with Instaloader from a public Instagram media link",
    }


def _image_payload(post):
    return {
        "kind": "image",
        "media_url": post.url,
        "mime_type": "image/jpeg",
        "title": _build_title(post),
        "description": "Resolved with Instaloader from a public Instagram media link",
    }


def _sidecar_node_payload(post, node):
    if getattr(node, "is_video", False) and getattr(node, "video_url", None):
        return {
            "kind": "video",
            "media_url": node.video_url,
            "mime_type": "video/mp4",
            "title": _build_title(post),
            "description": "Resolved with Instaloader from a public Instagram carousel",
        }

    display_url = getattr(node, "display_url", None)
    if display_url:
        return {
            "kind": "image",
            "media_url": display_url,
            "mime_type": "image/jpeg",
            "title": _build_title(post),
            "description": "Resolved with Instaloader from a public Instagram carousel",
        }

    return None


def resolve_instagram_shortcode(shortcode):
    shortcode = (shortcode or "").strip().strip("/")
    if not shortcode:
        raise ValueError("Empty Instagram shortcode")

    loader = instaloader.Instaloader(
        download_comments=False,
        save_metadata=False,
        download_video_thumbnails=False,
        post_metadata_txt_pattern="",
        quiet=True,
    )

    post = instaloader.Post.from_shortcode(loader.context, shortcode)

    if getattr(post, "is_video", False) and getattr(post, "video_url", None):
        payload = _video_payload(post)
        payload["items"] = [dict(payload)]
        return json.dumps(payload)

    if getattr(post, "typename", "") == "GraphSidecar":
        items = []
        for node in post.get_sidecar_nodes():
            item = _sidecar_node_payload(post, node)
            if item:
                items.append(item)
        if items:
            payload = dict(items[0])
            payload["title"] = _build_title(post)
            payload["description"] = "Resolved with Instaloader from a public Instagram carousel"
            payload["items"] = items
            return json.dumps(payload)

    payload = _image_payload(post)
    payload["items"] = [dict(payload)]
    return json.dumps(payload)
