# Translating OpenCode Mobile

OpenCode Mobile ships in English and 56 other languages on iOS and Android, and CI checks that
every language is complete. Whoever adds or changes copy keeps the translations current in the
same change, with the `translate` skill (`.agents/skills/translate/SKILL.md`). Hex Code isn't
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

Add the key on both platforms. When the key doesn't make the text's role obvious, write one
sentence above the Android string (or in the catalog's comment) saying what it is and what each
placeholder holds: `<!-- Button on the archive dialog. %1$s is the session title. -->`. The
translator reads these notes. A short comment without a full stop is a heading for the strings
under it that share its first keys' prefix.

Then follow the translate skill: `scripts/translate.mjs tasks` writes what's missing as small
tasks, an agent translates them, and `apply` checks the answers and writes both apps.
`status` shows what's missing without writing anything. Without an agent, push the English alone and say so in
the pull request; a maintainer translates before merge.

When the English of a key changes, `l10n/source-hashes.json` (the English each key was
translated from) marks it stale, and CI fails until it is translated again. For a typo fix that
keeps the meaning, keep the translations with `node scripts/translate.mjs accept <key>`.

## How it works

- `scripts/translate.mjs` calls no model. Each key is translated once and written to both apps
  when their English matches; the few keys that use each platform's own words (Face ID vs
  fingerprint) are translated per platform.
- A task lists each string once with its notes, and one code line when it is a bare short
  string with no note. Per language it adds the form of address and the words for the terms
  the strings use: `l10n/terms.json` (each language's chosen words), then
  `l10n/desktop-terms.json` (the OpenCode desktop app's, imported with
  `scripts/l10n/import-desktop-terms.mjs`). `l10n/glossary.json` gives the product terms'
  meanings and the words kept in English. A task covers a few strings for up to 10 languages,
  same script and plural forms together.
- The agent pulls more context with `scripts/l10n/lookup.mjs` only for strings that need it,
  and checks its answer with `scripts/l10n/check-answer.mjs`. Errors are what CI would reject:
  a missing string, a broken placeholder or plural. Hints are worth a second look: a term
  translated with another word than the language's, a different translation of the same English
  elsewhere in the app, stray spaces, a dropped … or ·.
- `apply` checks every answer again with the rules of `scripts/check-translations.mjs`
  (`scripts/l10n/rules.mjs`), writes the ones that pass, records the keys its tasks covered in
  `l10n/source-hashes.json` (a stale key only once every language has it again) and runs
  `check-translations.mjs`.
- The translation step runs best in one fresh subagent: every agent turn re-sends its context,
  so a long feature session pays for each one. A small change takes about four turns: `tasks`
  (it prints small tasks in full), the answers, `check-answer.mjs <task folder>`, `apply`.
- `scripts/l10n/consistency.mjs` and `scripts/l10n/term-sweep.mjs` are one-off passes after a
  full translation of a language: they pick one word per term and apply it everywhere, and
  `consistency.mjs --save` records the choices in `l10n/terms.json`.

## Fixing a translation by hand

Edit `values-<code>/strings.xml` and the catalog entry for the same key, then run the check.
Translation only fills gaps and changed English, so it keeps hand edits.

## What stays English

Server and session content (titles, messages, file names, model names), logs, and developer-only
screens.
