#!/usr/bin/env python3
"""A stand-in for `adb` used by the tests. It logs every call and replies from
files in $FAKE_ADB_DIR: dump.xml (screen), focus.txt (app in front),
install.txt (install output)."""
import json
import os
import sys

d = os.environ["FAKE_ADB_DIR"]
args = sys.argv[1:]
with open(os.path.join(d, "log.txt"), "a") as f:
    f.write(json.dumps(args) + "\n")
if args[:1] == ["-s"]:
    args = args[2:]


def read(name, default=""):
    path = os.path.join(d, name)
    return open(path).read() if os.path.exists(path) else default


verb = args[0] if args else ""
if verb == "devices":
    print("List of devices attached")
    print(read("devices.txt", "localhost:40123\tdevice").strip())
elif verb == "shell":
    cmd = " ".join(args[1:])
    if cmd.startswith("uiautomator dump"):
        print("UI hierchary dumped to: /data/local/tmp/fun-installer-ui.xml")
    elif cmd.startswith("dumpsys window"):
        pkg = read("focus.txt", "org.example.emu").strip()
        print(f"  mCurrentFocus=Window{{4b2 u0 {pkg}/{pkg}.MainActivity}}")
    elif cmd.startswith("monkey"):
        print("Events injected: 1")
    elif cmd.startswith("wm size"):
        print("Physical size: 1600x2560")
    elif cmd.startswith("pm list packages"):
        print(read("packages.txt", "package:org.example.emu\npackage:com.termux").strip())
elif verb == "exec-out":
    if args[1] == "cat":
        sys.stdout.write(read("dump.xml"))
    elif args[1] == "screencap":
        sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 8 + (1600).to_bytes(4, "big")
                                + (2560).to_bytes(4, "big"))
elif verb == "connect":
    print(f"connected to {args[1]}")
elif verb == "mdns":
    print(read("mdns.txt", "List of discovered mdns services").strip())
elif verb == "pair":
    if args[1] == read("pair_target.txt", args[1]).strip() and args[2] == "123456":
        print(f"Successfully paired to {args[1]} [guid=adb-XYZ]")
    else:
        print(f"Failed: Wrong password or connection was dropped.")
elif verb in ("install", "install-multiple"):
    print(read("install.txt", "Performing Streamed Install\nSuccess").strip())
    if "Success" in read("install.txt", "Success"):
        with open(os.path.join(d, "packages.txt"), "a") as f:
            f.write("\npackage:org.example.newapp")
