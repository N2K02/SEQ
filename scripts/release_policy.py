#!/usr/bin/env python3
"""SEQ stable and experimental versions: same one-decimal scheme as mRadio."""
import argparse
import re

_VERSION = re.compile(r"^[0-9]+\.[0-9]$")


def normalize(version: str) -> str:
    result = version.removeprefix("v")
    if not _VERSION.fullmatch(result):
        raise ValueError("version must look like 1.0, one decimal digit")
    return result


def plan(channel: str, previous: str, requested: str, run: int,
         pr: int = 1, sha: str = "1234567") -> dict[str, str]:
    if channel not in ("stable", "experimental") or not 0 < run < 2_000_000_000:
        raise ValueError("invalid release channel or run")
    if not (1 <= pr < 1_000_000):
        raise ValueError("invalid pull request number")
    base = normalize(previous) if previous else "0.3"
    if channel == "experimental":
        if requested:
            raise ValueError("only stable releases may request a version")
        version = base
        tag = f"experimental-pr-{pr}-run-{run}"
        name = f"SEQ-PR-{pr}-{sha[:7]}.apk"
        variant = "Debug"
        file = "app/build/outputs/apk/debug/app-debug.apk"
    else:
        if requested:
            version = normalize(requested)
        else:
            parts = base.split(".")
            tenth = int(parts[0]) * 10 + int(parts[1]) + 1
            version = f"{tenth // 10}.{tenth % 10}"
        if tuple(map(int, version.split("."))) <= tuple(map(int, base.split("."))):
            raise ValueError("stable version must increase from the public stable version")
        tag = f"v{version}"
        name = f"SEQ-{version}.apk"
        variant = "Release"
        file = "app/build/outputs/apk/release/app-release-unsigned.apk"
    return {
        "SEQ_CHANNEL": channel, "SEQ_VERSION": version,
        "SEQ_VERSION_CODE": str(100000 + run), "SEQ_TAG": tag,
        "SEQ_APK_NAME": name, "SEQ_BUILD_VARIANT": variant,
        "SEQ_INPUT_APK": file
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--channel", choices=["stable", "experimental"], required=True)
    p.add_argument("--previous", default="")
    p.add_argument("--requested", default="")
    p.add_argument("--run", type=int, required=True)
    p.add_argument("--pr", type=int, default=1)
    p.add_argument("--sha", default="1234567")
    a = p.parse_args()
    for k, v in plan(a.channel, a.previous, a.requested, a.run, a.pr, a.sha).items():
        print(f"{k}={v}")
