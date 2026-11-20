# Manual setup (if the one-command setup doesn't work)

These are the same steps [the one-command setup](../README.md#setup-one-command)
runs, done by hand.

### 1. Install Termux

From [F-Droid](https://f-droid.org/packages/com.termux/) or
[GitHub releases](https://github.com/termux/termux-app/releases).

### 2. Install Ubuntu inside Termux

```sh
pkg update -y && pkg upgrade -y
pkg install -y proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

The prompt changes to `root@localhost`, which means you're inside Ubuntu.
Run the next steps there.

### 3. Download Fun Installer and run setup

```sh
apt update && apt install -y git
git clone -b claude/sleepy-cannon-1gfvo8 https://github.com/shimonkolodny/Fun-Installer ~/Fun-Installer
bash ~/Fun-Installer/setup/setup.sh
```

### 4. Turn on Wireless debugging

1. **Settings → About tablet → tap "Build number" 7 times** to unlock Developer
   options. On Samsung it's under About tablet → Software information.
2. **Settings → System → Developer options → Wireless debugging → On.** Allow
   it for your Wi-Fi network.

### 5. Pair (once)

Android cancels the pairing code the moment Settings leaves the screen, so
you enter it from a notification (or in split screen, if your tablet allows
Settings there).

1. Install the
   [Fun Installer Pairing helper](https://github.com/shimonkolodny/Fun-Installer/releases/download/pairing-helper/FunInstallerPairing.apk)
   and open it once (allow notifications).
2. In Termux (inside Ubuntu), start waiting for the code:
   ```sh
   phone pair --wait
   ```
3. In the helper, tap **Open Developer options** → **Wireless debugging** →
   **Pair device with pairing code**.
4. Pull down the notification shade. On **Fun Installer pairing**, tap
   **Enter pairing code** and type the 6-digit code. (Or, in split screen,
   type it into Termux.)
5. When it says Paired, connect and keep Termux alive in the background:
   ```sh
   phone connect
   phone fix-background
   ```

`phone pair` finds the pairing port by itself. If it can't, run
`phone pair <port> <code>` with the port shown after the colon in the dialog.

### 6. Start Claude and sign in

```sh
fun-installer
```

Sign in with your Claude account (Pro or Max). Next time, open Termux and type
`fun`.

## More fixes

| Problem | Fix |
|---|---|
| `phone connect` can't find the port | Give it yourself: `phone connect <port>`, using the port under **IP address & Port** on the Wireless debugging screen. |
| Setup warns that adb is too old | Wireless pairing needs adb 30 or newer. Check with `adb version`. Reinstalling Ubuntu (`proot-distro reset ubuntu`) gets a newer one. |
| Claude can't "see" buttons in a game or emulator | Normal: they draw their own screens. Claude switches to screenshots there. |
