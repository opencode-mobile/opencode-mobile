# Hex Code store screenshots

Eight frames per store: four app screens, each followed by a graphic. Every
screen comes from the app's sample sessions (`TodaySessionDemo`), so no real
session, project or computer shows up.

## The strip

In store order. The first two matter most: they're all most people see in
search results. A screen comes first, because Apple wants screenshots to show
the app in use.

| # | Kind | What it shows | Caption |
|---|---|---|---|
| 1 | Screen | Holding the talk button, the words appearing as text | Tell OpenCode what you want |
| 2 | Graphic | The request as you say it, over the talk button ("Listening…") and loud voice bars | None: the quote is the headline |
| 3 | Screen | Idle card with a bulleted summary of the last reply | One short summary instead of a long chat |
| 4 | Graphic | A long chat receding into the distance behind the one summary card | No chat to scroll back through |
| 5 | Screen | Idle card with the report it made attached under the summary | Its charts and reports come attached |
| 6 | Graphic | Four of the app's artifact tiles, big, in a straight column | Charts, mock‑ups and reports, one tap away |
| 7 | Screen | Home, OpenCode working: status card, to-do progress | Know what it's working on |
| 8 | Graphic | The goal's target icon, and an example goal in the goal box, "active · round 7 of 50" | Give it a goal, and it keeps going |

Screen 5 shows the artifact as the tile attached to the summary, not open
in the viewer: a full-screen report reads as part of Hex Code's own design.
The sample artifact itself (`sample-artifact.html`) loads with no server, so
tapping the tile in a sample session still opens it.

The graphics use only the app's own look: its dark colour tokens, white
Inter Tight, and the artifact tile and goal box as the app draws them. They
stay in white and greys, with no accent colour, so the four read as one
set. The goal in frame 8 is in
quotes inside the goal box, so it reads as an example of what you might say,
not as a promise about the app.

The permission sheet (the `approval` preset) and the question sheet
(`needs`) are left out: Play allows eight phone screenshots, and the goal
says more about how you steer the agent.

Captions are plain sentences, set the same way:

- One sentence, no full stop, in sentence case.
- All white (`text1`, `#f7f8f8`), Inter Tight semibold, letter spacing
  −0.025 em, centred, balanced across one or two lines.

The screens sit on the app's near-black (`#08090a`) with one row of rounded
voice bars behind them, and each phone shows its whole screen in a dark
frame.

## Sizes

| Store | Device | Size (portrait) | Count |
|---|---|---|---|
| App Store | 6.9″ iPhone (required; Apple scales it down for smaller iPhones) | 1320 × 2868 | 8 (up to 10 allowed) |
| Google Play | Phone | 1080 × 2160 (2:1, the longest Play allows) | 8 (the most allowed) |

No iPad or tablet screenshots: Hex Code has no tablet layout yet. On
Google Play, leave the tablet slots empty.

## Other graphics

| Asset | Size | Source |
|---|---|---|
| App Store icon | 1024 × 1024, no transparency | Hex Code's app icon in its asset catalog |
| Google Play icon | 512 × 512 PNG | Exported from the same source |
| Google Play feature graphic | 1024 × 500 | Made with `npm run graphics:editor`, with the Hex Code mark and the line "Voice remote for OpenCode" |

## How they're made

The final images are in `screenshots/ios/` (1320 × 2868) and
`screenshots/android/` (1080 × 2160), named in store order (`1-talk.jpg` to
`8-goal.jpg`). The raw captures the screens are framed from are in
`screenshots/raw/`.

### 1. Capture the screens

Debug builds, dark appearance, with a clean status bar.

**iOS:** use the iPhone 16 Pro Max simulator, whose screen is natively
1320 × 2868. Set the status bar with
`xcrun simctl status_bar <udid> override --time 9:41 --batteryState discharging --batteryLevel 100 --wifiBars 3 --cellularBars 4`.
Then launch with these arguments and wait about 8 s, so the launch
animation's Dynamic Island has gone:

| Raw capture | Launch arguments |
|---|---|
| `1-talk` | `-HexCodeDemoSetup YES -HexCodeDemoSession artifact -HexCodeDemoTake locked` |
| `2-summary` | `-HexCodeDemoSetup YES -HexCodeDemoSession bullets` |
| `3-artifact` | `-HexCodeDemoSetup YES -HexCodeDemoSession artifact` |
| `4-progress` | `-HexCodeDemoSetup YES -HexCodeDemoSession card` |

**Android:** use the `store_phone` emulator (1080 × 2424). Turn on System
UI demo mode: clock `0941`, battery 100 and not plugged in, notifications
hidden. Snooze the Safety Center "screen lock" notification if it shows.
Then launch with `adb shell am start -S -n dev.opencode.hexcode/.MainActivity`
and these extras:

| Raw capture | Extras |
|---|---|
| `1-talk` | `--ez HexCodeDemoSetup true --es HexCodeSampleSession artifact --es HexCodeDemoTake locked` |
| `2-summary` | `--ez HexCodeDemoSetup true --es HexCodeSampleSession bullets` |
| `3-artifact` | `--ez HexCodeDemoSetup true --es HexCodeSampleSession artifact` |
| `4-progress` | `--ez HexCodeDemoSetup true --es HexCodeSampleSession card` |

Save the captures as `raw/<ios|android>/<name>.png`. (`raw/*/5-approve.png`,
the permission sheet, is kept but isn't in the strip.)

### 2. Frame the screens and draw the graphics

Two pages draw one frame each, at the store size, with headless Chrome:

- [The screen framer, `render.html`](screenshots/render.html), draws the
  wave, the caption and the phone. Pass it `?p=ios|android&i=1..4`; run it
  from a folder that has `raw/` next to it.
- [The graphics page, `graphics.html`](screenshots/graphics.html), draws a
  graphic. Pass it `?p=ios|android&f=voice|chat|artifacts|goal`.

For example:

```bash
chrome --headless=new --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=6000 --window-size=1320,2868 \
  --screenshot=ios_1.png "file://$PWD/render.html?p=ios&i=1"
```

Use `--window-size=1080,2160` for Android. Save the results as JPEG, at
quality 92 with no alpha, in `screenshots/ios/` and `screenshots/android/`,
under these names:

| Frame | Page and parameter | File |
|---|---|---|
| 1 | `render.html`, `i=1` | `1-talk.jpg` |
| 2 | `graphics.html`, `f=voice` | `2-voice.jpg` |
| 3 | `render.html`, `i=2` | `3-summary.jpg` |
| 4 | `graphics.html`, `f=chat` | `4-no-chat.jpg` |
| 5 | `render.html`, `i=3` | `5-attached.jpg` |
| 6 | `graphics.html`, `f=artifacts` | `6-artifacts.jpg` |
| 7 | `render.html`, `i=4` | `7-progress.jpg` |
| 8 | `graphics.html`, `f=goal` | `8-goal.jpg` |
