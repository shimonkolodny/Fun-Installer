# Fun Installer app (retired experiment)

> **Not recommended.** This standalone Android app used the paid Claude API with
> Haiku 5.5 doing the tapping. In real use it went in circles (for example,
> bouncing around inside ZArchiver for 15 minutes), and it costs money per job.
> Use the [Claude Code version](../README.md) instead, which runs on your Claude
> subscription and handles files with commands. The code stays in `android/`
> for reference. Its build no longer runs automatically and is no longer
> signed with a shared key.

An Android app that installs apps, emulators and emulator mods for you. Tell it
what you want; it finds the official download, installs it, and taps through the
setup screens itself while you watch.

## Get started (about 5 minutes)

1. **Download the app on your tablet.** In the tablet's browser, sign in to
   GitHub (this repo is private), open
   **[Releases → latest](https://github.com/shimonkolodny/Fun-Installer/releases/latest)**
   and download **FunInstaller.apk**.
2. **Install it.** Open the downloaded file. If Android asks, allow your browser
   to install apps. If Play Protect warns about an unknown app, tap
   **More details → Install anyway**. It warns because the app isn't from the
   Play Store and it uses Accessibility.
3. **Open Fun Installer and do the 3 setup steps it shows:**
   1. **Claude API key:** create one at
      [console.anthropic.com → API keys](https://console.anthropic.com/settings/keys)
      and add some credit to your account.
   2. **Let it control the screen:** turn on Fun Installer in Accessibility
      settings. If the switch is greyed out, open App info, tap **⋮** →
      **Allow restricted settings**, then try again.
   3. **Allow installing apps:** turn on "Allow from this source".
4. **Ask for something,** for example *"Install the PPSSPP emulator and set my
   games folder to Download/Games"*, then tap **Make a plan**. Read the plan,
   and tap **Start**.

## How it works

1. **Planning:** Claude Sonnet 5.5 searches the web for the official download
   and a current guide, then writes a step-by-step plan. You see every download
   link and step before anything happens.
2. **Doing:** Claude Haiku 5.5 carries out the plan one tap at a time: it reads
   the screen, taps, types and scrolls. When it gets stuck, Sonnet takes over for
   a moment with a screenshot, then hands back.
3. **Watching:** a small floating card shows what it's doing and what it has cost
   so far. It has a **Stop** button. Drag the card by its title if it's in the
   way.
4. **Your turn:** you always tap **Install** on Android's install screens and
   answer permission pop-ups yourself. The card asks before system settings
   change, and tells you when something is yours to do, like signing in or
   providing your own game files.
5. **It remembers:** after each job it saves notes on what worked, so similar
   jobs go better next time.

### What it costs

You pay Anthropic for the AI use, billed to your API key. Rough estimates:

| Mode | Who does what | Typical job |
|---|---|---|
| **Saver** (default) | Sonnet plans and helps when stuck, Haiku taps | a few cents to well under $1 |
| **Best quality** | Opus 5.5 plans and helps, Sonnet taps | several times more |

Every job has a **spending limit** (default $1.00, set it in the app). Fun
Installer asks before going over it, and shows the running total on the card.

## Safety

These are enforced by the app's code, whatever the AI decides:

- **Downloads only from the plan you approved.** New links mid-job need your OK.
- **You confirm every install and every permission pop-up.** The AI waits while
  those screens are open.
- **No purchases or passwords.** It won't tap buy/subscribe buttons or type into
  password fields.
- **Private apps are off limits.** Banking, payment, password, authenticator,
  messaging and email apps can't be read or controlled.
- **Official sources only, no BIOS, firmware, keys or games.** The AI is told
  this, and it hands those files to you to provide.
- **To stop instantly:** tap **Stop** on the card, or turn off Fun Installer in
  Accessibility.

## Limits right now

- It can't write directly into other apps' private folders
  (`/sdcard/Android/data/…`) or open `.7z`/`.rar` archives. It puts files in
  `/sdcard/Download/FunInstaller/` and uses the app's own import or folder
  options instead.
- Games and some emulator menus draw their own screens, so the app has to use
  screenshots there, which is slower and costs a little more.
- It's new and hasn't been tested on many tablets yet. Expect rough edges and
  tell Claude what went wrong.

## Updating

Download the latest **FunInstaller.apk** again and install it over the old one.
Your settings and API key are kept.

## Troubleshooting

| Problem | Fix |
|---|---|
| Accessibility switch is greyed out | App info → **⋮** → **Allow restricted settings**, then turn it on. |
| "Your Claude API key was rejected" | Check the key in the app (Settings → Change API key) and that your Anthropic account has credit. |
| The card disappeared mid-job | Android may have turned the accessibility service off. Turn it back on and start the job again. |
| It keeps trying the same thing | Tap Stop, then try again with a more specific request. The details log (Show details) shows what happened. |

## For developers

- `android/` is the app: Kotlin, no UI libraries, official Anthropic Java SDK.
  [GitHub Actions](../.github/workflows/android.yml) builds it on every push and
  publishes the APK as the `latest` release.
- Main files: `Agent.kt` (step loop and rescue), `Plan.kt` (planner),
  `Screen.kt` (reads the screen into numbered elements), `AgentService.kt`
  (accessibility actions), `Safety.kt` (rules enforced in code),
  `Prompts.kt` and `Tools.kt` (what Claude is told and can do).
- The older Claude Code version for Termux (`bin/phone`, `setup/`, `.claude/`)
  still works and needs no API key. See
  [the main README](../README.md).
