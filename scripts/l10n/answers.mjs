// Reading an agent's answer to a translation or review task.

// The answer for one language: {"<field>": {"<ios code>": {"<id>": …}}}. An answer that covers
// one language may leave out the language level.
export function answerFor(answer, field, language, single) {
  const all = answer?.[field] ?? {};
  const own = all[language.ios];
  if (own && typeof own === "object" && !Array.isArray(own)) return own;
  return single ? all : {};
}

// A string, or for a plural the language's forms; null when the value has the wrong shape. A
// plain string for a plural is the wrong shape: one text can't be right for every form in
// languages whose nouns change with the count.
export function normalizeAnswer(value, language, plural) {
  if (value === undefined || value === null) return null;
  if (plural) {
    if (typeof value !== "object") return null;
    const forms = {};
    for (const q of language.plurals) if (typeof value[q] === "string") forms[q] = value[q];
    return forms;
  }
  return typeof value === "string" ? value : null;
}

// The JSON object in a file's text, ignoring anything around it (a code fence, a sentence).
export function parseAnswerText(text) {
  return JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
}
