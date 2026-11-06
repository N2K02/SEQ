#!/usr/bin/env python3
"""Validate the GitHub release asset name against the published experimental feed.

Standard library only; CI uses gh release view for live asset checks before
writing a feed. This executable script independently catches the old #label bug.
"""
import json
import sys
from urllib.parse import urlsplit, unquote


def validate(manifest: dict, release_asset_names: list[str]) -> None:
    url = urlsplit(manifest["apkUrl"])
    filename = unquote(url.path.rsplit("/", 1)[-1])
    if url.scheme != "https" or url.netloc != "github.com":
        raise ValueError("APK URL must be HTTPS GitHub Releases")
    if not filename.lower().endswith(".apk"):
        raise ValueError("Update URL is not an APK")
    if filename not in release_asset_names:
        raise ValueError(f"Update manifest points to a missing asset: {filename}")
    if manifest["tag"] not in url.path:
        raise ValueError("Update URL and manifest tag disagree")
    if len(manifest.get("sha256", "")) != 64 or manifest.get("apkSize", 0) <= 0:
        raise ValueError("Missing SHA-256 or size")


if __name__ == "__main__":
    manifest = json.load(open(sys.argv[1], encoding="utf-8"))
    validate(manifest, sys.argv[2:])
    print("SEQ updater manifest matches published asset.")
