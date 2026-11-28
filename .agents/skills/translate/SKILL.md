---
name: translate
description: "Translations: use after adding or changing app copy, or to fix, review or check the 56 translations."
---

# Translate

OpenCode Mobile ships in English and 56 languages on iOS and Android. Whoever changes the copy
keeps the translations current in the same change. `scripts/translate.mjs` finds the work,
writes it as small task files and applies the answers; you do the translating. Run commands
from the repository root.

## Steps

1. **Status.** `node scripts/translate.mjs status`. Done when it says every language is up to
   date. A key whose English changed shows as stale and is translated again; for a typo fix
   that keeps the meaning, keep the translations instead with
   `node scripts/translate.mjs accept <key>`.
2. **Tasks.** `node scripts/translate.mjs tasks`. It prints the task files and the folder they
   are in. A task covers a few strings for up to 10 languages.
3. **Work every task.** Up to 3 tasks: work them yourself, one after another. More: one
   subagent per task, at most 8 at a time, each told: "Load the translate skill and work the
   task in `<task file>`. Reply with one line: done, and any string you were unsure about."
   One task per subagent keeps each context small. For each task:
   - Read the task file. Translate by the rules below, using the task's notes, words and form
     of address.
   - Look up a string only when it's unclear: a single word that could be a verb or a noun, a
     placeholder whose value you can't tell, a term you don't know how this app says. Ask for
     every string and language you need in one call:
     `node scripts/l10n/lookup.mjs <id>,<id> <code>,<code>` (code usage, nearby strings and
     their translations, each language's words) or
     `node scripts/l10n/lookup.mjs --find "<English words>" <code>,<code>` (how the app already
     says a word or phrase).
   - Write the answer to the task's answer file, then run its check command. The task is done
     when the check reports 0 errors and you have weighed every hint: change a string only
     where the hint points at a real problem.
4. **Apply.** `node scripts/translate.mjs apply <folder>`. Done when it reports no failed or
   unanswered strings; otherwise fix those answers and apply again.
5. **Confirm.** `node scripts/check-translations.mjs` passes. Commit the changed
   `values-*/strings.xml`, `Localizable.xcstrings`, `InfoPlist.xcstrings` and
   `l10n/source-hashes.json` with the feature.

Edit the string files only through `apply`, so every translation passes the same checks as CI.

## Reviewing translations

`node scripts/translate.mjs tasks --review --keys a,b [codes]` writes review tasks for those
keys' current translations; then work and apply them as above. `--review --all` reviews every
string in every language, millions of tokens: keep it for a new language or a large rewrite.
To fix one known-bad translation, edit `values-<code>/strings.xml` and the catalog entry by hand
and run the check.

## Translation rules

The users are software developers; the app remotely controls OpenCode, an AI coding agent on
their computer.

- Translate the meaning the way a well-made native app in that language says it. Short, plain,
  friendly. Buttons take the language's usual button form (imperative or infinitive).
- Read each string's id, note and `used_in` to tell a button from a title, label, status or
  sentence, and a verb from a noun.
- Placeholders `{1}`, `{2}`… appear exactly as often as in the English; they may move. Read each
  as filled in with a real value and make the sentence correct around it.
- Keep as written: the task's "Keep as written" list, slash commands, shell commands, file
  names, paths, code, model and provider names, and text in backticks.
- Use the language's "Words" and form of address so the string matches the rest of the app, and
  its "Desktop app words" so it matches the OpenCode desktop app. Follow the "Product terms"
  meanings. Developer terms the language's developers keep in English (commit, branch, diff,
  prompt, token) may stay English; "shell" never becomes an eggshell or seashell word.
- Keep line breaks, the ellipsis character … and the middle dot · where the English has them.
  Use the language's own quotation marks and punctuation.
- Buttons, chips, tabs and short labels stay short.
- A plural answers with exactly the language's plural forms (the section lists them), each the
  full text for that CLDR count category: for example one = 1, 21; few = 2–4, 22–24;
  many = 5–20 for Slavic languages. The count placeholder may be left out of a form only where
  the language says the number as a word.
- A string that is already language-neutral (a symbol, a brand name alone) stays as it is.

Reviewing, fix a translation only when it is wrong by these rules, or reads unnatural, as a
calque, in the wrong register, or still in English where the language would translate it.
Leave correct translations alone.

## Answer format

One JSON object in the answer file, nothing around it:

- Translation: `{"translations": {"<code>": {"<id>": "<text>" or {"<form>": "<text>"}}}}`, every
  language and every id the task lists.
- Review: `{"fixes": {"<code>": {"<id>": …}}, "reasons": {"<code>": {"<id>": "<few words>"}}}`,
  only the ids you changed; `{"fixes": {}, "reasons": {}}` when all are right.
