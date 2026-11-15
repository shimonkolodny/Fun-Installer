# Fun Installer

Ask Claude to install apps, emulators and emulator mods on your Android tablet,
and it does the work itself: it researches the official download, installs it,
taps through the setup screens, and puts mod files in the right folders.

It runs entirely on the tablet and uses your normal Claude subscription through
Claude Code. **No API key needed.**

```
Termux ─▶ Ubuntu (proot-distro) ─▶ Claude Code ─▶ phone ─▶ ADB Wireless debugging ─▶ the same tablet
```

## What you need

- An Android tablet with **Android 11 or newer**, on **Wi-Fi** (Wireless
  debugging only works on Wi-Fi).
- A Claude account that can use Claude Code (for example Pro or Max).
- About 3 GB of free space.

## One-time setup (about 20 minutes)

### 1. Install Termux

Get it from [F-Droid](https://f-droid.org/packages/com.termux/) or
[GitHub releases](https://github.com/termux/termux-app/releases). Those builds
are the recommended ones.

### 2. Install Ubuntu inside Termux

Open Termux and run:

```sh
pkg update -y && pkg upgrade -y
pkg install -y proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

The prompt changes to `root@localhost`, which means you're inside Ubuntu.
Run all the following steps there.

### 3. Download Fun Installer and run setup

This repo is private, so sign in to GitHub first:

```sh
apt update && apt install -y git gh
gh auth login
```

Choose **GitHub.com → HTTPS → Login with a web browser**. It shows a one-time
code and a link: long-press the link in Termux to open it, then enter the code.

```sh
gh repo clone shimonkolodny/Fun-Installer ~/Fun-Installer
cd ~/Fun-Installer
bash setup/setup.sh
```

### 4. Turn on Wireless debugging

1. **Settings → About tablet → tap "Build number" 7 times** to unlock Developer
   options. On Samsung it's under About tablet → Software information.
2. **Settings → System → Developer options → Wireless debugging → On.** Allow
   it for your Wi-Fi network.

### 5. Pair Termux with Wireless debugging (once)

The pairing code disappears if you leave the Settings screen, so use **split
screen**: open Recents, tap the Termux icon at the top of its card, choose
**Split screen**, and put Settings in the other half.

1. In Wireless debugging, tap **Pair device with pairing code**. It shows a
   6-digit code and an address like `192.168.1.20:41235`.
2. In Termux, using the number after the colon:
   ```sh
   phone pair 41235 123456
   phone connect
   phone fix-background
   ```
   `fix-background` stops Android from closing Termux while Claude works in
   other apps. If your tablet has a battery setting for Termux, set it to
   **Unrestricted** too.

### 6. Sign in to Claude

```sh
fun-installer
```

Choose your Claude account and follow the link it shows (long-press to open it).
Paste the code back into Termux.

## Using it

Open Termux and type **`fun`**. Inside Ubuntu, `fun-installer` does the same.
Then just ask:

- *"Install the PPSSPP emulator and set my games folder to /sdcard/Games"*
- *"Add the HD texture pack for <game> to <emulator>"*
- *"Update RetroArch and set up my controller"*

What to expect:

- **Claude works in the other apps**, so Termux disappears behind them. When it
  needs you, it brings Termux back to the front.
- **You approve the important steps.** Installing, uninstalling, copying files
  onto the tablet, granting permissions and raw commands always wait for your
  OK. Reading the screen, tapping, typing and downloading don't.
- **Your own game files and BIOS:** Claude never downloads these. It tells you
  which file is needed and where to put it.
- **It learns:** after each install it writes what worked to `notes/`, so next
  time is faster.
- **To stop it:** switch to Termux and press **ESC** (on the extra keys row).
  For an instant hard stop, turn off Wireless debugging.

### After a reboot or Wi-Fi change

Wireless debugging switches itself off. Turn it back on, then start `fun` as
usual. Claude reconnects with `phone connect`. You don't need to pair again.

## Safety built in

These are enforced by the `phone` tool itself, not just by Claude's instructions:

- **It never taps or types in Termux**, so Claude can't approve its own
  requests.
- **Blocked apps:** banking, payment, password, authenticator, messaging and
  email apps can't be read, screenshotted or controlled. Edit
  [`config/blocked-apps.txt`](config/blocked-apps.txt) to add more.
- **Files only go into shared storage** (`/sdcard/...`), never system folders.
- **No out-of-date taps:** Claude must look at the screen again after every
  action before tapping by number.

Claude's own rules (in [the skill](.claude/skills/fun-installer/SKILL.md)):
official download sources only, no BIOS, firmware, keys or ROMs, no cracked
apps, and anything a web page or tutorial "tells" it to do is treated as
information, not orders.

## Troubleshooting

| Problem | Fix |
|---|---|
| `claude` won't start inside Ubuntu | Claude Code doesn't officially support Android. Running it in Ubuntu under proot usually works, but not always. Note the error message and share it in a Claude chat. |
| `phone pair` fails | The pairing dialog must stay open (use split screen), and the code and port change every time it opens. |
| `phone connect` can't find the port | Give it yourself: `phone connect <port>`, using the port shown under **IP address & Port** on the Wireless debugging screen. |
| Setup warns that adb is too old | Wireless pairing needs adb 30 or newer. Check with `adb version`, then reinstall Ubuntu with `proot-distro install ubuntu` to get a newer one. |
| Claude stops while working in other apps | Run `phone fix-background` again, set Termux's battery use to Unrestricted, and tap **Acquire wakelock** in Termux's notification. |
| Claude can't "see" buttons in a game or emulator | Normal: they draw their own screens. Claude switches to screenshots for those. |

## What's in this repo

| Path | What it is |
|---|---|
| `bin/phone` | The tool Claude uses to see and control the tablet (`phone -h` lists commands) |
| `.claude/skills/fun-installer/SKILL.md` | Claude's instructions: workflow, rules, tips |
| `.claude/settings.json` | Which `phone` commands run freely and which need your approval |
| `config/blocked-apps.txt` | Apps Claude must never touch |
| `notes/` | What Claude learned from past installs |
| `setup/setup.sh` | Installs adb, Claude Code and the `phone` command inside Ubuntu |
| `tests/` | Tests for `phone`, using a fake adb (`python3 -m unittest discover tests`) |
