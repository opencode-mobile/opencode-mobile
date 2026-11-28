---
name: translate
description: "Translations: use after adding or changing app copy, or to fix, review or check the 56 translations."
---

# Translate

OpenCode Mobile ships in English and 56 languages on iOS and Android. Whoever changes the copy
keeps the translations current in the same change. `scripts/translate.mjs` finds the work,
writes it as small tasks and applies the answers; you do the translating. Run commands from the
repository root.

Run the whole translation step in one fresh subagent (Sonnet is enough) rather than in a long
feature session: every turn re-sends the agent's context, and translating needs nothing from
the feature session beyond the string notes. Tell it: "Load the translate skill and translate
the app's missing strings. Reply with what you applied and any string you were unsure about."

## Steps

1. **Tasks.** `node scripts/translate.mjs tasks`. It prints the tasks in full when they are
   small, or lists the task files. "Nothing to translate" ends the job. Add
   `--redo <key>` for a key whose English changed meaning without its text changing much; a
   changed English text is picked up on its own. For a typo fix that keeps the meaning, keep
   the translations instead: `node scripts/translate.mjs accept <key>`.
2. **Answer every task.** Translate by the rules below, using each task's notes, words and form
   of address. Write all the answer files in one turn. Look a string up only when it is unclear
   (a single word that could be a verb or a noun, a placeholder whose value you can't tell, a
   term you don't know how this app says), with every string and language you need in one
   call: `node scripts/l10n/lookup.mjs <id>,<id> <code>,<code>` (code usage, nearby strings
   and their translations, each language's words) or
   `node scripts/l10n/lookup.mjs --find "<English words>" <code>,<code>`.
   More than about 6 task files: give each one to its own subagent (at most 8 at a time) with
   "Load the translate skill and answer the task in `<task file>`; check it with
   check-answer.mjs. Reply with one line."
3. **Check.** `node scripts/l10n/check-answer.mjs <task folder>` checks every answer. Done when
   it reports 0 errors and you have weighed every hint. A hint is a question, not a rule:
   dismiss it when the string means something else there (the other string it names may be a
   different role).
4. **Apply.** `node scripts/translate.mjs apply <task folder>` checks again, writes both apps,
   updates `l10n/source-hashes.json` and runs `scripts/check-translations.mjs`. Done when it
   reports no failed strings and the check passes. Commit the changed `values-*/strings.xml`,
   `Localizable.xcstrings`, `InfoPlist.xcstrings` and `l10n/source-hashes.json` with the
   feature. (`tasks` may already have written strings that one platform had and the other
   lacked; they belong in the same commit.)

`node scripts/translate.mjs status` shows what is missing or stale without writing anything,
and lists keys translated everywhere but not recorded in `l10n/source-hashes.json`.

## Reviewing or fixing translations

`node scripts/translate.mjs tasks --review --keys a,b [codes]` writes review tasks for those
keys' current translations; answer, check and apply them as above. Fix one known-bad
translation the same way, with its key and language. `--review --all` reviews every string in
every language, millions of tokens: keep it for a new language or a large rewrite.

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
