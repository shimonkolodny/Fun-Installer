#!/data/data/com.termux/files/usr/bin/bash
# Fun Installer: one-step setup on an Android tablet.
# Paste this into the Termux app:
#
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/shimonkolodny/Fun-Installer/refs/heads/claude/sleepy-cannon-1gfvo8/setup/termux-bootstrap.sh)"
#
# It's safe to run again: finished steps are skipped.
set -euo pipefail

REPO_URL="https://github.com/shimonkolodny/Fun-Installer"
BRANCH="${FUN_INSTALLER_BRANCH:-claude/sleepy-cannon-1gfvo8}"
DISTRO="ubuntu"

step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
note() { printf '%s\n' "$*"; }
in_ubuntu() { proot-distro login "$DISTRO" -- bash -lc "$*"; }
# Throw away keys pressed while setup was busy, so they don't end up in a prompt.
flush_input() { while read -r -t 0.1 -n 10000 _ 2>/dev/null; do :; done || true; }

if [ -z "${PREFIX:-}" ] || [ ! -d /data/data/com.termux ]; then
  echo "Please run this inside the Termux app on your tablet."
  exit 1
fi

step "Fun Installer setup (about 15 minutes, mostly waiting)"
note "Keep the tablet awake, plugged in if you can, and on Wi-Fi."
termux-wake-lock >/dev/null 2>&1 || true

step "Step 1 of 5: Updating Termux"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold full-upgrade
apt-get install -y proot-distro

step "Step 2 of 5: Installing Ubuntu inside Termux"
if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
  note "Already installed."
else
  proot-distro install "$DISTRO"
fi

step "Step 3 of 5: Installing Fun Installer and Claude Code"
in_ubuntu "export DEBIAN_FRONTEND=noninteractive FUN_BOOTSTRAP=1
  apt-get update -y && apt-get install -y git ca-certificates
  if [ -d ~/Fun-Installer/.git ]; then
    git -C ~/Fun-Installer pull --ff-only
  else
    git clone -b '$BRANCH' '$REPO_URL' ~/Fun-Installer
  fi
  bash ~/Fun-Installer/setup/setup.sh"

step "Step 4 of 5: Let Fun Installer control the tablet (Wireless debugging)"
if in_ubuntu "phone status" >/dev/null 2>&1 || in_ubuntu "phone connect" >/dev/null 2>&1; then
  note "Already connected."
else
  cat <<'EOF'
This is the only fiddly part, and you only do it once.

IMPORTANT: the pairing code is cancelled the moment Settings leaves the
screen. So Termux and Settings must be on screen together (split screen),
and you type the code into the Termux half without switching apps.

  1. If "Developer options" isn't in Settings yet: open Settings >
     About tablet > tap "Build number" 7 times, then come back here.
     (Samsung: About tablet > Software information > Build number.)
  2. Press Enter below. Settings should open next to Termux.
     If it opens full-screen instead: tap the ••• at the top center of
     the screen (or open Recents and tap the app's icon) > Split screen,
     and put Termux in the other half.
  3. In the Settings half: Wireless debugging > turn it ON (allow it for
     this Wi-Fi), then tap "Pair device with pairing code".
  4. Tap the Termux half and type the 6-digit code shown in Settings.
EOF
  flush_input
  read -rp $'\nPress Enter to open Developer options next to Termux... ' _
  # FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_LAUNCH_ADJACENT: on tablets this opens
  # Settings in the other half of a split screen. Fall back to a normal launch.
  am start -f 0x10001000 -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null 2>&1 ||
    am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null 2>&1 || true
  while true; do
    flush_input
    read -rp $'\nType the 6-digit pairing code (or just press Enter to skip for now): ' raw
    if [ -z "$raw" ]; then
      note "Skipped. Later, inside Ubuntu, run: phone pair <code>   then: phone connect"
      break
    fi
    code="${raw//[^0-9]/}"   # ignore stray keys (arrows, spaces) typed along with it
    if [[ ! "$code" =~ ^[0-9]{6}$ ]]; then
      note "The code is 6 digits, like 123456. Try again."
      continue
    fi
    if in_ubuntu "phone pair $code && phone connect"; then
      in_ubuntu "phone fix-background" || true
      note "Connected. Also set Termux's battery use to 'Unrestricted' if your tablet has that setting."
      break
    fi
    note "That didn't work. If the code in Settings is different now, the dialog was"
    note "closed when Settings left the screen. Keep both apps in split screen, tap"
    note "\"Pair device with pairing code\" again, then tap the Termux half and type the new code."
  done
fi

step "Step 5 of 5: Sign in to Claude"
cat <<'EOF'
Claude Code starts next. Choose to sign in with your Claude account
(Pro or Max), open the link it shows (long-press it), then paste the
code back here.

Then just ask, for example:
  "Install the PPSSPP emulator and set my games folder to Download/Games"

Next time: open Termux and type  fun
EOF
read -rp $'\nPress Enter to start Claude... ' _
exec proot-distro login "$DISTRO" -- /usr/local/bin/fun-installer
