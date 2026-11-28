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
   platforms, and takes a minute or two for a few strings. Add `--review` to have a second
   model check what it just translated.
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
- A request lists each string once with its key, its notes and one code line that uses it,
  then a short section per language: its plural forms, its form of address and its words for
  the terms those strings use. The words come from `l10n/terms.json` (the consistency pass's
  choices), then the OpenCode desktop app's words (`l10n/desktop-terms.json`, imported with
  `scripts/l10n/import-desktop-terms.mjs`). The glossary (`l10n/glossary.json`) gives the
  product terms' meanings.
- A few changed strings (up to 20) go out for many languages in one request, so their context
  is sent once: one changed string is one request of a few thousand tokens for all 56
  languages. A bigger run sends one language per request, about 60 strings from the same
  screens, with some of that language's translations from those screens.
- `--dry-run` prints the first request and how much the whole run would send.
- Placeholders reach the model as `{1}`, `{2}`, and go back as each platform's `%1$s` or
  `%1$@`. Plurals get the CLDR forms each language needs.
- Every answer is checked with the same rules as `check-translations.mjs`
  (`scripts/l10n/rules.mjs`). A string that fails three times stays in English and the check
  lists it.
- `--review` has a second model check the strings this run translated and fix only wrong ones;
  `--review --keys a,b` checks those keys. `--review-all` checks every translation in every
  language, a run of millions of tokens, and records it in `l10n/review-status.json`. Keep it
  for a new language or a large rewrite.
- `scripts/l10n/consistency.mjs` and `scripts/l10n/term-sweep.mjs` are one-off passes after a
  full translation: they pick one word per term and apply it everywhere. They aren't needed
  for later changes, which get the chosen words from `l10n/terms.json`.

Models: `--model` (default `claude-agent/sonnet`) and `--review-model` (default
`claude-agent/opus`) take any `provider/model` that `opencode run -m` accepts. `claude-agent`
keeps a local `claude` process alive per call, so the script sends its calls to a private
`opencode serve` and restarts it every 25 requests, which ends those processes. A memory guard
pauses new requests while free memory is under 30% or more than 12 local agent processes run;
`--jobs` (default 3) sets how many requests run at once. Keep emulators, simulators and Gradle
builds closed during a full run.

### Handing the work to agents

`--handoff <dir>` writes the requests as agent tasks instead of calling a model, and
`--answers <dir>` checks and applies the answers. This script stays the only thing that writes
the string files.

A task holds what most strings need: each string with its notes, and each language's form of
address and words for the terms it uses. The agent pulls more only for strings that need it:

- `node scripts/l10n/lookup.mjs <id>,<id> <language>,<language>` shows where each string is used
  in code (with the function or view around it), the strings near it with their translations,
  its current translations, and each language's words for its terms. One call takes several
  strings and languages, since each call costs the agent a turn.
- `node scripts/l10n/lookup.mjs --find "<English words>" <language>,<language>` shows how the app
  already translates a word or phrase.

A bare short string with no note ("Open", "{1} queued") gets one code line in the task itself,
since an agent would otherwise have to look it up or guess. A task covers at most 10 languages,
with languages of the same script and plural forms together.

The agent then runs `node scripts/l10n/check-answer.mjs <task>.answer.json` on its answer and
fixes what it reports before handing back. Errors are what CI would reject (a missing string, a
broken placeholder, a plural answered with one text) and language codes the task doesn't have.
Hints are worth a second look: a translation that doesn't use the language's word for a term,
one that differs from how the app translates the same English elsewhere, stray spaces, or a
dropped … or ·. The checker can't catch a wrong sense ("Offen" for the verb "Open"); the
same-English hint and the code line on bare short strings are the defence there.

Give each agent one task file. An agent keeps every file it reads in its context and sends all
of it again on each turn, so one agent working through a list of files costs many times what
the files themselves do.

## Fixing a translation by hand

Edit `values-<code>/strings.xml` and the catalog entry for the same key, then run the check. The
script only fills gaps and changed English, so it keeps hand edits.

## What stays English

Server and session content (titles, messages, file names, model names), logs, and developer-only
screens.
