#!/usr/bin/env bash
# Fun Installer setup. Run this inside Ubuntu (proot-distro) on the tablet:
#     bash setup/setup.sh
# It is safe to run again; it skips anything already done.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*"; }

if [ ! -f /etc/os-release ]; then
  echo "This must run inside Ubuntu, not plain Termux. In Termux run:"
  echo "    proot-distro install ubuntu && proot-distro login ubuntu"
  echo "then run this script again from inside Ubuntu. See README.md."
  exit 1
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  if command -v sudo >/dev/null 2>&1; then SUDO="sudo"; else
    echo "Please run as root (the default inside proot-distro)."; exit 1
  fi
fi

say "Installing tools: adb, python3, curl, git, unzip, 7-Zip"
export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update -y
$SUDO apt-get install -y --no-install-recommends \
  adb python3 curl ca-certificates git unzip p7zip-full
# Newer 7-Zip (opens RAR5 archives). Optional: not every release has it.
$SUDO apt-get install -y --no-install-recommends 7zip >/dev/null 2>&1 || true
adb version | head -1
if ! adb help 2>&1 | grep -q "pair"; then
  warn "This adb is too old for wireless pairing (needs version 30+). See README.md > Troubleshooting."
fi

say "Installing Claude Code"
export PATH="$HOME/.local/bin:$PATH"
if command -v claude >/dev/null 2>&1; then
  echo "Already installed: $(claude --version 2>/dev/null || echo 'version unknown')"
else
  curl -fsSL https://claude.ai/install.sh | bash
fi
grep -qs '.local/bin' "$HOME/.bashrc" || echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
if ! claude --version >/dev/null 2>&1; then
  warn "Claude Code was installed but didn't start. See README.md > Troubleshooting."
fi

say "Installing the 'phone' and 'fun-installer' commands"
$SUDO tee /usr/local/bin/phone >/dev/null <<EOF
#!/bin/sh
exec python3 "$REPO/bin/phone" "\$@"
EOF
$SUDO tee /usr/local/bin/fun-installer >/dev/null <<EOF
#!/bin/bash
# Starts Claude Code in the Fun Installer folder.
export PATH="\$HOME/.local/bin:\$PATH"
cd "$REPO"
exec claude "\$@"
EOF
$SUDO chmod +x /usr/local/bin/phone /usr/local/bin/fun-installer
mkdir -p "$REPO/downloads" "$REPO/screens"

say "Adding a 'fun' shortcut to Termux"
TERMUX_HOME=/data/data/com.termux/files/home
if [ -d "$TERMUX_HOME" ] && [ -w "$TERMUX_HOME" ]; then
  if grep -qs "alias fun=" "$TERMUX_HOME/.bashrc"; then
    echo "Already there."
  else
    cat >> "$TERMUX_HOME/.bashrc" <<'EOF'

# Fun Installer: type 'fun' in Termux to start Claude on this tablet.
alias fun='termux-wake-lock; proot-distro login ubuntu -- /usr/local/bin/fun-installer'
EOF
    echo "Done. In a new Termux session, type: fun"
  fi
else
  echo "Couldn't reach Termux's home folder. Skipping (you can still start with: fun-installer)."
fi

# The one-step Termux setup (termux-bootstrap.sh) walks through the rest itself.
[ -n "${FUN_BOOTSTRAP:-}" ] && exit 0

cat <<EOF

$(printf '\033[1;32m')Setup finished.$(printf '\033[0m') Next steps (details in README.md):
  1. Pair once with Wireless debugging:   phone pair <code>
  2. Connect:                             phone connect
  3. Keep Termux alive in the background: phone fix-background
  4. Start Claude:                        fun-installer
     (later, just type 'fun' in a new Termux session)
EOF
