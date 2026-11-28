# Translating OpenCode Mobile

OpenCode Mobile ships in English and 56 other languages on iOS and Android. A model translates
the text through `opencode run`, and CI checks that every language is complete. Hex Code isn't
translated yet.

## Where the text is

| Platform | English | Translations |
| --- | --- | --- |
| Android | `android/app/src/main/res/values/strings.xml` | `values-<code>/strings.xml` next to it |
| iOS | `ios/OpenCode Mobile/OpenCode Mobile/Localizable.xcstrings` | the same catalog, one localization per language |
| iOS permission prompts | `AppTransportSecurity.plist` (`NS…UsageDescription`) | `InfoPlist.xcstrings` |

Keys match across platforms: iOS `area.name` is Android `area_name`
(`scripts/check-string-keys.mjs`). The languages, with their Android and iOS codes, are in
`scripts/l10n/languages.mjs`. Right-to-left languages wait for a right-to-left layout pass.

## Adding or changing English text

1. Add the key on both platforms, as before. Above an Android string, or in the catalog's
   comment, say what the text is when the key doesn't make it obvious: a button or a title,
   what `%1$s` holds, a width limit. The translator reads these notes.
2. Run `node scripts/translate.mjs all`. It translates only what's missing, writes both
   platforms, and takes a minute or two for a few strings.
3. Run `node scripts/check-translations.mjs`. CI runs it in String Checks.

When the English of an existing key changes, the next `translate.mjs all` translates it again:
`l10n/source-hashes.json` records the English each key was translated from. For a typo fix that
doesn't change the meaning, keep the translations with
`node scripts/translate.mjs --accept <key>`.

Without model access, push the English alone and say so in the pull request; a maintainer runs
the script.

## How the script translates

- Each key is translated once and written to both apps when their English matches. The few keys
  that use each platform's own words (Face ID vs fingerprint) are translated per platform.
- A request carries about 60 strings from the same screens, each with its key, its notes, the
  code lines that use it, the language's existing translations from the same area, the
  glossary (`l10n/glossary.json`) and the OpenCode desktop app's words for shared terms
  (`l10n/desktop-terms.json`, imported with `scripts/l10n/import-desktop-terms.mjs`).
- Placeholders reach the model as `{1}`, `{2}`, and go back as each platform's `%1$s` or
  `%1$@`. Plurals get the CLDR forms each language needs.
- Every answer is checked with the same rules as `check-translations.mjs`
  (`scripts/l10n/rules.mjs`). A string that fails three times stays in English and the check
  lists it.
- `--review` has a second model read every translation with its context and fix only wrong
  ones. `l10n/review-status.json` records the last review of each language.

Models: `--model` (default `claude-agent/sonnet`) and `--review-model` (default
`claude-agent/opus`) take any `provider/model` that `opencode run -m` accepts. `claude-agent`
keeps a local `claude` process alive per call, so the script sends its calls to a private
`opencode serve` and restarts it every 25 requests, which ends those processes. A memory guard
pauses new requests while free memory is under 30% or more than 12 local agent processes run;
`--jobs` (default 3) sets how many requests run at once. Keep emulators, simulators and Gradle
builds closed during a full run.

## Fixing a translation by hand

Edit `values-<code>/strings.xml` and the catalog entry for the same key, then run the check. The
script only fills gaps and changed English, so it keeps hand edits.

## What stays English

Server and session content (titles, messages, file names, model names), logs, and developer-only
screens.
