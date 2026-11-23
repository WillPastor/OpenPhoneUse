"""Check assistant startup twice on an unlocked test device with OpenPhoneUse as its assistant.

Use the plain input path (e.g. "Talk right away" or accessibility off). Start from Home.
Set ANDROID_SERIAL to select a device and ADB if adb is outside PATH, then run:

    python bench/test_assistant_sheet.py
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.phoneuse"


def shell(*args, timeout=12):
    return subprocess.run(
        [os.environ.get("ADB", "adb"), "shell", *args],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    ).stdout.strip()


def hierarchy():
    # uiautomator needs a regular file. Remove it after reading the tree.
    output = shell("""dump=$(mktemp /data/local/tmp/assistant-sheet.XXXXXX) || exit 1
        trap 'rm -f "$dump"' EXIT
        uiautomator dump "$dump" >/dev/null && cat "$dump" """)
    return ET.fromstring(output) if output else None


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else (0, 0, 0, 0)


def editor_matches(tree, text):
    root = tree.find("node")
    if root is None:
        return False
    x1, y1, x2, y2 = bounds(root)
    for node in tree.findall(f".//node[@package='{PACKAGE}'][@class='android.widget.EditText']"):
        left, top, right, bottom = bounds(node)
        visible = max(x1, left) < min(x2, right) and max(y1, top) < min(y2, bottom)
        if visible and node.get("focused") == "true" and node.get("text") == text:
            return True
    return False


def wait_for(predicate, failure):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        tree = hierarchy()
        if tree is not None and predicate(tree):
            return
        time.sleep(0.2)
    raise RuntimeError(failure)


def main():
    service = shell("settings", "get", "secure", "voice_interaction_service")
    if service.split("/")[0] != PACKAGE:
        raise RuntimeError("Select OpenPhoneUse as the test device's default assistant first.")

    for session in (1, 2):
        try:
            shell("input", "keyevent", "KEYCODE_ASSIST")
            wait_for(lambda tree: editor_matches(tree, ""),
                     f"Session {session}: an empty, focused assistant editor did not appear on screen.")
            sample = f"sheetcheck{session}"
            shell("input", "text", sample)
            wait_for(lambda tree: editor_matches(tree, sample),
                     f"Session {session}: the assistant editor did not receive the text.")
        finally:
            shell("input", "keyevent", "KEYCODE_HOME")
        wait_for(lambda tree: tree.find(f".//node[@package='{PACKAGE}']") is None,
                 f"Session {session}: the assistant sheet did not dismiss.")
        print(f"Session {session} opened, accepted text without a tap, and dismissed.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        sys.exit(str(error))
