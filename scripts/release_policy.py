#!/usr/bin/env python3
"""Strict stable/experimental version and tag policy for SEQ GitHub Actions."""
import argparse
import re

VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def validate(version: str) -> tuple[int, int, int]:
    if not VERSION.fullmatch(version):
        raise ValueError("version must be MAJOR.MINOR.PATCH")
    values = tuple(int(part) for part in version.split("."))
    if any(part > 9999 for part in values):
        raise ValueError("version number is unreasonable")
    return values


def plan(channel: str, previous: str, requested: str, run: int) -> dict[str, str]:
    if channel not in ("stable", "experimental") or not 0 < run < 2_000_000_000:
        raise ValueError("invalid release channel or run number")
    previous = previous.removeprefix("v")
    old = validate(previous) if previous else None
    if requested and channel != "stable":
        raise ValueError("explicit stable versions are only valid on main")
    if channel == "stable":
        if requested:
            ver = validate(requested)
            if old is not None and ver <= old:
                raise ValueError("stable version must advance beyond last stable tag")
        else:
            ver = (0, 3, 0) if old is None else (old[0], old[1], old[2] + 1)
    else:
        ver = old or (0, 3, 0)
    version = ".".join(map(str, ver))
    tag = f"v{version}" if channel == "stable" else f"experimental-pr-1-run-{run}"
    name = f"SEQ-{version}.apk" if channel == "stable" else f"SEQ-{version}-exp-{run}.apk"
    return {"SEQ_CHANNEL": channel, "SEQ_VERSION": version,
            "SEQ_VERSION_CODE": str(100000 + run), "SEQ_TAG": tag, "SEQ_APK_NAME": name}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--channel", choices=["stable", "experimental"], required=True)
    parser.add_argument("--previous", default="")
    parser.add_argument("--requested", default="")
    parser.add_argument("--run", type=int, required=True)
    args = parser.parse_args()
    for key, value in plan(args.channel, args.previous, args.requested, args.run).items():
        print(f"{key}={value}")
