"""Capture actual WireRoute UI on the designated screenshot emulator.

Requires adb on PATH or ANDROID_ADB. Never connects a VPN or edits app files.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
ADB = os.environ.get("ANDROID_ADB", str(sdk / "platform-tools/adb") if sdk.exists() else "adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], text=True).strip()


def nodes():
    result = adb("shell", "uiautomator", "dump", "/sdcard/wireroute-listing.xml")
    if "dumped to" not in result:
        raise SystemExit("Android could not provide a fresh UI hierarchy; inspect the screen before retrying.")
    xml = adb("exec-out", "cat", "/sdcard/wireroute-listing.xml")
    return list(ET.fromstring(xml).iter("node"))


def tap(label):
    matches = [n for n in nodes() if label in (n.get("text"), n.get("content-desc"))]
    if not matches:
        raise SystemExit(f"No visible UI control matching {label!r}")
    node = matches[0]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)


def run(command, args):
    if command == "tap":
        for label in args:
            tap(label)
    elif command == "dump":
        print(json.dumps([
            {k: n.get(k) for k in ("text", "content-desc", "bounds", "clickable")}
            for n in nodes() if n.get("text") or n.get("content-desc")
        ], indent=2))
    elif command == "shot":
        time.sleep(1)
        destination = Path(args[0]).resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        adb("shell", "screencap", "-p", "/sdcard/wireroute-listing.png")
        print(adb("pull", "/sdcard/wireroute-listing.png", str(destination)))
    elif command == "back":
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    elif command == "sequence":
        for action in args:
            verb, separator, value = action.partition(":")
            run(verb, [value] if separator else [])
    elif command == "demo":
        for fields in (
            ("command", "exit"),
            ("command", "enter"),
            ("command", "clock", "hhmm", "0941"),
            ("command", "notifications", "visible", "false"),
            ("command", "battery", "level", "100", "plugged", "false"),
            ("command", "network", "wifi", "show", "level", "4", "fully", "true", "mobile", "hide", "sims", "0", "nosim", "hide"),
        ):
            extras = [item for pair in zip(fields[::2], fields[1::2]) for item in ("--es", *pair)]
            adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extras)
    else:
        raise SystemExit("Use dump, tap LABEL..., shot PATH, back, or sequence ACTION...")


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    run(command, args)
