"""Tests for bin/phone, run against tests/fake_adb.py instead of a real device.

    python3 -m unittest discover tests
"""
import importlib.machinery
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
PHONE = HERE.parent / "bin" / "phone"
FAKE_ADB = HERE / "fake_adb.py"
SAMPLE = (HERE / "sample_screen.xml").read_text()


def load_phone_module():
    loader = importlib.machinery.SourceFileLoader("phone_mod", str(PHONE))
    spec = importlib.util.spec_from_loader("phone_mod", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


class PhoneTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.fake = self.tmp / "fake"
        self.fake.mkdir()
        (self.fake / "dump.xml").write_text(SAMPLE)
        os.chmod(FAKE_ADB, 0o755)
        self.env = dict(os.environ, PHONE_ADB=str(FAKE_ADB), FAKE_ADB_DIR=str(self.fake),
                        PHONE_STATE_DIR=str(self.tmp / "state"),
                        PHONE_DOWNLOADS=str(self.tmp / "downloads"),
                        PHONE_SCREENS=str(self.tmp / "screens"), PHONE_SETTLE="0")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def phone(self, *args):
        return subprocess.run([sys.executable, str(PHONE), *args], env=self.env,
                              capture_output=True, text=True, timeout=60)

    def log(self):
        return [json.loads(line) for line in (self.fake / "log.txt").read_text().splitlines()]

    def shell_cmds(self):
        return [c[-1] for c in self.log() if "shell" in c]

    def focus(self, pkg):
        (self.fake / "focus.txt").write_text(pkg)

    # ---------------------------------------------------------------- screen
    def test_screen_lists_numbered_elements(self):
        r = self.phone("screen")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout
        self.assertIn("App: org.example.emu", out)
        self.assertIn('[0] TextView "Emulator Setup"', out)
        self.assertIn('[1] ImageButton "Settings" #settings @1520,140 [tap]', out)
        self.assertIn('[3] LinearLayout > "Games folder" / "Not set" #row @800,300 [tap]', out)
        self.assertIn("[5] Switch #toggle @1480,500 [tap,off]", out)
        self.assertIn('hint="Folder path"', out)
        self.assertIn("[edit", out.replace("[tap,long,edit", "[edit"))
        self.assertIn('[8] Button "Download later"', out)
        self.assertIn("disabled", out)
        # Text inside a tappable row is folded into the row, not listed again.
        self.assertNotIn('TextView "Games folder"', out)

    def test_screen_hides_termux(self):
        (self.fake / "dump.xml").write_text(SAMPLE.replace("org.example.emu", "com.termux"))
        r = self.phone("screen")
        self.assertIn("(not shown)", r.stdout)
        self.assertNotIn("Download", r.stdout)

    # ------------------------------------------------------------------- tap
    def test_tap_by_exact_text(self):
        r = self.phone("tap", "Download")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input tap 410 2060", self.shell_cmds())

    def test_tap_text_inside_row(self):
        r = self.phone("tap", "Games folder")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input tap 800 300", self.shell_cmds())

    def test_tap_ambiguous_text_lists_choices(self):
        r = self.phone("tap", "load")
        self.assertEqual(r.returncode, 2)
        self.assertIn("2 elements match", r.stderr)
        self.assertFalse(any(c.startswith("input tap") for c in self.shell_cmds()))
        r = self.phone("tap", "load", "--nth", "1")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_tap_by_index_needs_fresh_screen(self):
        self.phone("screen")
        r = self.phone("tap", "7")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input tap 410 2060", self.shell_cmds())
        r = self.phone("tap", "1")
        self.assertEqual(r.returncode, 1)
        self.assertIn("may have changed", r.stderr)

    def test_tap_by_percent(self):
        r = self.phone("tap", "--pct", "50", "25")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input tap 800 640", self.shell_cmds())

    def test_long_press(self):
        r = self.phone("tap", "Download", "--long")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input swipe 410 2060 410 2060 900", self.shell_cmds())

    # -------------------------------------------------------------- safety
    def test_never_taps_termux(self):
        self.focus("com.termux")
        r = self.phone("tap", "10", "10")
        self.assertEqual(r.returncode, 1)
        self.assertIn("Termux", r.stderr)
        self.assertFalse(any(c.startswith("input") for c in self.shell_cmds()))

    def test_never_types_into_termux(self):
        self.focus("com.termux")
        r = self.phone("type", "y", "--enter")
        self.assertEqual(r.returncode, 1)
        self.assertFalse(any(c.startswith("input") for c in self.shell_cmds()))

    def test_blocked_app(self):
        self.focus("com.mybank.mobile")
        r = self.phone("tap", "5", "5")
        self.assertEqual(r.returncode, 1)
        self.assertIn("blocked list", r.stderr)

    def test_open_termux_refused(self):
        r = self.phone("open", "com.termux")
        self.assertEqual(r.returncode, 1)

    def test_power_key_refused(self):
        r = self.phone("key", "KEYCODE_POWER")
        self.assertEqual(r.returncode, 1)

    def test_push_only_to_shared_storage(self):
        f = self.tmp / "mod.txt"
        f.write_text("x")
        r = self.phone("push", str(f), "/data/data/org.example.emu/")
        self.assertEqual(r.returncode, 1)
        self.assertIn("shared storage", r.stderr)
        r = self.phone("push", str(f), "/sdcard/../data/x")
        self.assertEqual(r.returncode, 1)

    # ---------------------------------------------------------- type & keys
    def test_type_escapes_spaces_and_quotes(self):
        r = self.phone("type", "my games", "--enter")
        self.assertEqual(r.returncode, 0, r.stderr)
        cmds = self.shell_cmds()
        self.assertIn("input text my%sgames", cmds)
        self.assertIn("input keyevent 66", cmds)
        self.phone("type", "it's")
        self.assertIn("input text 'it'\"'\"'s'", self.shell_cmds())

    def test_type_rejects_non_ascii(self):
        r = self.phone("type", "café")
        self.assertEqual(r.returncode, 1)

    def test_scroll_down(self):
        self.phone("screen")
        r = self.phone("scroll", "down")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("input swipe 800 2176 800 384 400", self.shell_cmds())

    # --------------------------------------------------------------- install
    def test_install_reports_new_app(self):
        apk = self.tmp / "app.apk"
        apk.write_bytes(b"PK fake")
        r = self.phone("install", str(apk))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("New app: org.example.newapp", r.stdout)

    def test_install_failure_explains(self):
        (self.fake / "install.txt").write_text(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package signatures do not match]")
        apk = self.tmp / "app.apk"
        apk.write_bytes(b"PK fake")
        r = self.phone("install", str(apk))
        self.assertEqual(r.returncode, 1)
        self.assertIn("DELETES its data", r.stderr)

    # ----------------------------------------------------- pairing & loops
    def test_pair_with_code_only_finds_port(self):
        (self.fake / "mdns.txt").write_text(
            "List of discovered mdns services\nadb-XYZ\t_adb-tls-pairing._tcp\t192.168.1.5:37011")
        (self.fake / "pair_target.txt").write_text("localhost:37011")
        r = self.phone("pair", "123456")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Paired with localhost:37011", r.stdout)
        self.assertIn(["pair", "localhost:37011", "123456"], self.log())

    def test_pair_moves_on_when_a_port_hangs(self):
        # The connection port makes `adb pair` hang; the real pairing port comes after it.
        (self.fake / "mdns.txt").write_text(
            "List of discovered mdns services\n"
            "adb-XYZ\t_adb-tls-pairing._tcp\t192.168.1.5:45653\n"
            "adb-XYZ\t_adb-tls-pairing._tcp\t192.168.1.5:39001")
        (self.fake / "hang_target.txt").write_text("localhost:45653")
        (self.fake / "pair_target.txt").write_text("localhost:39001")
        self.env["PHONE_PAIR_TIMEOUT"] = "1"
        r = self.phone("pair", "123456")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Paired with localhost:39001", r.stdout)

    def test_connect_moves_on_when_a_port_hangs(self):
        (self.fake / "devices.txt").write_text("")
        (self.fake / "hang_target.txt").write_text("localhost:45653")
        self.env["PHONE_PAIR_TIMEOUT"] = "1"
        r = self.phone("connect", "45653")
        self.assertEqual(r.returncode, 1)
        self.assertNotIn("Traceback", r.stderr)

    def test_pair_rejects_bad_code(self):
        r = self.phone("pair", "12ab")
        self.assertEqual(r.returncode, 1)
        self.assertIn("6-digit", r.stderr)

    def test_warns_when_going_in_circles(self):
        other = SAMPLE.replace("Emulator Setup", "Other Screen")
        for i in range(6):
            (self.fake / "dump.xml").write_text(SAMPLE if i % 2 == 0 else other)
            r = self.phone("screen")
        self.assertIn("going in circles", r.stdout)

    def test_no_loop_warning_for_normal_progress(self):
        for i in range(6):
            (self.fake / "dump.xml").write_text(SAMPLE.replace("Emulator Setup", f"Screen {i}"))
            r = self.phone("screen")
        self.assertNotIn("going in circles", r.stdout)

    # ---------------------------------------------------------------- misc
    def test_shot_records_size(self):
        r = self.phone("shot")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("1600x2560", r.stdout)

    def test_sniff_detects_web_page(self):
        phone = load_phone_module()
        page = self.tmp / "download.apk"
        page.write_text("\n  <!DOCTYPE html><html><body>Click here</body></html>")
        self.assertIn("WEB PAGE", phone.sniff(page))


if __name__ == "__main__":
    unittest.main()
