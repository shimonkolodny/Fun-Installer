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
HELPER_URL="https://github.com/shimonkolodny/Fun-Installer/releases/download/pairing-helper/FunInstallerPairing.apk"

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
  cat <<END_OF_HELP
This is the only fiddly part, and you only do it once.

IMPORTANT: Android cancels the pairing code the moment Settings leaves the
screen, so you have to enter it WITHOUT switching to another app.

The easy way (works on every tablet): the Fun Installer Pairing helper.
It lets you type the code into a notification, and pulling down the
notification shade doesn't count as leaving Settings.

  1. If "Developer options" isn't in Settings yet: Settings > About tablet >
     tap "Build number" 7 times. (Samsung: About tablet > Software
     information > Build number.)
  2. Type h below to download the helper, then install it and open it.
     (Link: $HELPER_URL)
  3. In the helper, tap "Open Developer options". Turn on Wireless
     debugging, then tap "Pair device with pairing code".
  4. Pull down the notification shade. On "Fun Installer pairing", tap
     "Enter pairing code", type the 6-digit code and send it.
  5. When it says Paired, come back here.

If your tablet lets Settings share the screen (split screen), you can
instead type the code right here in Termux while both are on screen.
END_OF_HELP
  flush_input
  read -rp $'\nType h and Enter to download the helper (or just Enter if you have it): ' ans
  if [[ "${ans,,}" == h* ]]; then
    termux-open-url "$HELPER_URL" >/dev/null 2>&1 || note "Open this link in your browser: $HELPER_URL"
  fi
  # Waits for the code: typed here, or sent from the helper's notification.
  rc=0
  in_ubuntu "phone pair --wait" || rc=$?
  if [ "$rc" -eq 0 ]; then
    if in_ubuntu "phone connect"; then
      in_ubuntu "phone fix-background" || true
      note "Connected. Also set Termux's battery use to 'Unrestricted' if your tablet has that setting."
    else
      note "Paired, but couldn't connect yet. Check Wireless debugging is on; Claude retries with: phone connect"
    fi
  else
    note "Skipped for now. Later, inside Ubuntu, run: phone pair --wait   then: phone connect"
  fi
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
