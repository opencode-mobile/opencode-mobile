# Hex Code

> [!IMPORTANT]
> **Hex Code is an experiment, and it is not an official opencode app.** It is
> an independent community project, not made by, affiliated with, or endorsed
> by the [opencode](https://github.com/sst/opencode) project. It lives on the
> `feature/hex-experiment` branch, is not in the app stores, and may change or
> break at any time.

Talk to the coding agent on your computer, from your phone.

Hex Code is a voice-first app for [OpenCode](https://opencode.ai), for iPhone
and Android. You hold a button, say what you want, and let go. The agent on
your computer does the work, and the phone shows you one short card: what it
did, what it's doing now, and whether it needs you.

It is made for the moments when you are away from the keyboard: on a walk, on
the sofa, making coffee while a long task runs.

## What you can do with it

- **Speak, don't type.** Hold the talk button and speak; slide up to lock it
  for a long take. Your words are turned into text on the phone itself, in
  about a second. Your voice never leaves the phone.
- **See where things stand at a glance.** The home screen is one status card:
  a short summary of the agent's latest work, its to-do list as it ticks
  along, and links you can open. There is no long chat to scroll through.
- **Steer while it works.** Send your message after the agent's current turn,
  or right now as a correction. You have three seconds to undo.
- **Answer when it needs you.** When the agent asks for permission or has a
  question, a sheet comes up. Allow once, allow always, or deny and say why,
  so the agent carries on in another way.
- **Stop it.** Hold Stop for three seconds and the agent and its subagents
  stop.
- **Give it a goal for the day.** Dictate a goal and the agent keeps working
  towards it, round after round. Pause, change or clear it at any time.
- **Ask on the side.** Ask a quick question about the work without
  interrupting what the agent is doing.
- **Show it things.** Attach photos, screenshots, videos or files. Photos go
  to the model to look at; files are copied to your computer for the agent to
  open.
- **Look at what it built.** Open your dev server's `localhost` pages and the
  agent's artifacts in the app's own browser, with no extra sign-in.

One session per project per day keeps things simple: open the app and you are
in today's session. You can start another from Settings.

## What you need

- A computer running **OpenCode 2.2.5 or newer** (`opencode upgrade` gets you
  the latest).
- An **iPhone with iOS 18** or an **Android phone with Android 8 or newer**.
- The phone and the computer on the **same network or the same
  [Tailscale](https://tailscale.com) network**. If they aren't,
  `opencode pair --remote` connects them through the internet.
- For now, a way to **build the app yourself**: Xcode for iPhone, Android
  Studio or the Android SDK for Android. See
  [Install the app](#install-the-app).

## Get started

1. **Install the app** on your phone (see below).
2. **On your computer, run:**

   ```bash
   opencode pair
   ```

   It shows a QR code. The code works once, for 5 minutes.
3. **In Hex Code, tap "Scan the code"** and point the phone at it. The app
   checks that it can reach your computer and that OpenCode is new enough.
4. **Pick a project**, or browse to a folder on your computer.
5. **Pick a model.** You can search, set the reasoning level and send a
   one-line test to see that it answers.
6. **Hold the talk button and say what you want.**

The speech model downloads in the background the first time, so the very first
take may wait a moment for it.

## Good to know

- **Your speech stays on the phone.** Dictation runs on the device (NVIDIA's
  Parakeet model). Only the text you send goes to your computer.
- **Nothing is hosted for you.** The app talks only to the OpenCode server on
  your own computer. The models, the code and the sessions stay where they
  are.
- **English only** for now, and **one computer at a time**. Pairing with
  another computer switches the app over to it.
- **No notifications yet** while the phone is locked. Open the app to see if
  the agent needs you.
- **iPhone and Android phones only.** No iPad or tablet layout yet.
- **You stay signed in for 30 days**, and the app renews this by itself each
  time you use it. If it says "Signed out", run `opencode pair` again and
  choose Settings › Server › Pair again.

## If something goes wrong

| The app says | What to do |
|---|---|
| Can't reach your computer | Put the phone on the same network or Tailscale as the computer, or use `opencode pair --remote`. |
| Your computer isn't reachable yet | On the computer, run `opencode service set hostname 0.0.0.0`, then `opencode pair` again. |
| This code was used or expired | Codes work once, for 5 minutes. Run `opencode pair` again. |
| OpenCode needs an update | Run `opencode upgrade` on the computer. |
| This address isn't encrypted | The address isn't on your network or Tailscale. Use `opencode pair --remote`. |
| Signed out of your computer | Run `opencode pair`, then Pair again in the app. |

Found a problem? Report it in
[this repository](https://github.com/opencode-mobile/opencode-mobile/issues),
not to opencode, and say it is about Hex Code.

## Install the app

Hex Code is not in the App Store or on Google Play. You build it from the
`feature/hex-experiment` branch:

```bash
git clone https://github.com/opencode-mobile/opencode-mobile.git
cd opencode-mobile
git checkout feature/hex-experiment
```

### Android

You need JDK 17 and the Android SDK. Connect your phone with USB debugging on,
then:

```bash
cd android
./gradlew :hexcode:installDebug
```

The first build downloads the speech engine, so it takes a little longer.

### iPhone

You need a Mac with Xcode. Open
`ios/OpenCode Mobile/OpenCode Mobile.xcodeproj`, choose the **Hex Code**
scheme and your iPhone, set your own team under Signing & Capabilities, and
press Run.

## For developers

Hex Code is a second app in the OpenCode Mobile repository. It shares the
network and data code with OpenCode Mobile and has its own screens and design.

| Path | What is there |
|---|---|
| `ios/OpenCode Mobile/Hex Code/` | The iPhone app (SwiftUI) |
| `android/hexcode/` | The Android app (Kotlin, Jetpack Compose) |
| `ios/OpenCodeKit/`, `android/opencode-kit/` | Network and data code shared with OpenCode Mobile |
| `shared/voice-engine/` | On-device dictation for both platforms; see its [README](../../shared/voice-engine/README.md) |

Settings › Developer in the app has a component gallery, a speech model lab
and a sample session that needs no server.

Progress, what was tested and what is still open are tracked in the draft pull
request [#980](https://github.com/opencode-mobile/opencode-mobile/pull/980).
For the repository's rules, read [CONTRIBUTING.md](../../CONTRIBUTING.md) and
[AGENTS.md](../../AGENTS.md).

## License

GPL v3, the same as the rest of the repository; see [LICENSE](../../LICENSE),
[NOTICE](../../NOTICE) and
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). The opencode name
belongs to the opencode project and is used here only to say what this app
connects to.
