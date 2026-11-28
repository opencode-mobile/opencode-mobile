// The languages OpenCode Mobile is translated into, on both platforms.
//
// The list matches the left-to-right languages the OpenCode desktop app and the official
// OpenCode Android app ship. Right-to-left languages (ar, he, fa, ur, dv) and Punjabi
// (Shahmukhi script on desktop) wait for a right-to-left layout pass.
//
// Fields:
//   android  res/values-<android> folder qualifier
//   ios      Localizable.xcstrings localization code, and the Xcode knownRegions entry
//   desktop  the OpenCode desktop app's code (packages/app/src/runtime/i18n/<desktop>.ts in
//            github.com/anomalyco/opencode), used to read its terminology
//   name     English name, used in model prompts
//   script   the writing system, used to warn about text in the wrong script
//   plurals  CLDR cardinal plural categories the language uses; Android <plurals> and
//            iOS plural variations need each of them

const ONE_OTHER = ["one", "other"];
const OTHER = ["other"];
const ONE_MANY_OTHER = ["one", "many", "other"];
const ONE_FEW_OTHER = ["one", "few", "other"];
const ONE_FEW_MANY_OTHER = ["one", "few", "many", "other"];

export const LANGUAGES = [
  { android: "am", ios: "am", desktop: "am", name: "Amharic", script: "Ethiopic", plurals: ONE_OTHER },
  { android: "az", ios: "az", desktop: "az", name: "Azerbaijani", script: "Latin", plurals: ONE_OTHER },
  { android: "bg", ios: "bg", desktop: "bg", name: "Bulgarian", script: "Cyrillic", plurals: ONE_OTHER },
  { android: "bn", ios: "bn", desktop: "bn", name: "Bengali", script: "Bengali", plurals: ONE_OTHER },
  { android: "bs", ios: "bs", desktop: "bs", name: "Bosnian", script: "Latin", plurals: ONE_FEW_OTHER },
  { android: "ca", ios: "ca", desktop: "ca", name: "Catalan", script: "Latin", plurals: ONE_MANY_OTHER },
  { android: "cs", ios: "cs", desktop: "cs", name: "Czech", script: "Latin", plurals: ONE_FEW_MANY_OTHER },
  { android: "da", ios: "da", desktop: "da", name: "Danish", script: "Latin", plurals: ONE_OTHER },
  { android: "de", ios: "de", desktop: "de", name: "German", script: "Latin", plurals: ONE_OTHER },
  { android: "dz", ios: "dz", desktop: "dz", name: "Dzongkha", script: "Tibetan", plurals: OTHER },
  { android: "el", ios: "el", desktop: "el", name: "Greek", script: "Greek", plurals: ONE_OTHER },
  { android: "es", ios: "es", desktop: "es", name: "Spanish", script: "Latin", plurals: ONE_MANY_OTHER },
  { android: "et", ios: "et", desktop: "et", name: "Estonian", script: "Latin", plurals: ONE_OTHER },
  { android: "fi", ios: "fi", desktop: "fi", name: "Finnish", script: "Latin", plurals: ONE_OTHER },
  { android: "fo", ios: "fo", desktop: "fo", name: "Faroese", script: "Latin", plurals: ONE_OTHER },
  { android: "fr", ios: "fr", desktop: "fr", name: "French", script: "Latin", plurals: ONE_MANY_OTHER },
  { android: "hi", ios: "hi", desktop: "hi", name: "Hindi", script: "Devanagari", plurals: ONE_OTHER },
  { android: "hr", ios: "hr", desktop: "hr", name: "Croatian", script: "Latin", plurals: ONE_FEW_OTHER },
  { android: "hu", ios: "hu", desktop: "hu", name: "Hungarian", script: "Latin", plurals: ONE_OTHER },
  { android: "hy", ios: "hy", desktop: "hy", name: "Armenian", script: "Armenian", plurals: ONE_OTHER },
  // Android folders keep the old ISO code `in` for Indonesian.
  { android: "in", ios: "id", desktop: "id", name: "Indonesian", script: "Latin", plurals: OTHER },
  { android: "is", ios: "is", desktop: "is", name: "Icelandic", script: "Latin", plurals: ONE_OTHER },
  { android: "it", ios: "it", desktop: "it", name: "Italian", script: "Latin", plurals: ONE_MANY_OTHER },
  { android: "ja", ios: "ja", desktop: "ja", name: "Japanese", script: "Japanese", plurals: OTHER },
  { android: "ka", ios: "ka", desktop: "ka", name: "Georgian", script: "Georgian", plurals: ONE_OTHER },
  { android: "km", ios: "km", desktop: "km", name: "Khmer", script: "Khmer", plurals: OTHER },
  { android: "ko", ios: "ko", desktop: "ko", name: "Korean", script: "Hangul", plurals: OTHER },
  { android: "lo", ios: "lo", desktop: "lo", name: "Lao", script: "Lao", plurals: OTHER },
  { android: "lt", ios: "lt", desktop: "lt", name: "Lithuanian", script: "Latin", plurals: ONE_FEW_MANY_OTHER },
  { android: "lv", ios: "lv", desktop: "lv", name: "Latvian", script: "Latin", plurals: ["zero", "one", "other"] },
  { android: "mk", ios: "mk", desktop: "mk", name: "Macedonian", script: "Cyrillic", plurals: ONE_OTHER },
  { android: "mn", ios: "mn", desktop: "mn", name: "Mongolian", script: "Cyrillic", plurals: ONE_OTHER },
  { android: "ms", ios: "ms", desktop: "ms", name: "Malay", script: "Latin", plurals: OTHER },
  { android: "my", ios: "my", desktop: "my", name: "Burmese", script: "Myanmar", plurals: OTHER },
  { android: "nb", ios: "nb", desktop: "no", name: "Norwegian Bokmål", script: "Latin", plurals: ONE_OTHER },
  { android: "ne", ios: "ne", desktop: "ne", name: "Nepali", script: "Devanagari", plurals: ONE_OTHER },
  { android: "nl", ios: "nl", desktop: "nl", name: "Dutch", script: "Latin", plurals: ONE_OTHER },
  { android: "pl", ios: "pl", desktop: "pl", name: "Polish", script: "Latin", plurals: ONE_FEW_MANY_OTHER },
  { android: "pt-rBR", ios: "pt-BR", desktop: "br", name: "Brazilian Portuguese", script: "Latin", plurals: ONE_MANY_OTHER },
  { android: "ro", ios: "ro", desktop: "ro", name: "Romanian", script: "Latin", plurals: ONE_FEW_OTHER },
  { android: "ru", ios: "ru", desktop: "ru", name: "Russian", script: "Cyrillic", plurals: ONE_FEW_MANY_OTHER },
  { android: "si", ios: "si", desktop: "si", name: "Sinhala", script: "Sinhala", plurals: ONE_OTHER },
  { android: "sk", ios: "sk", desktop: "sk", name: "Slovak", script: "Latin", plurals: ONE_FEW_MANY_OTHER },
  { android: "sl", ios: "sl", desktop: "sl", name: "Slovenian", script: "Latin", plurals: ["one", "two", "few", "other"] },
  { android: "sq", ios: "sq", desktop: "sq", name: "Albanian", script: "Latin", plurals: ONE_OTHER },
  { android: "sr", ios: "sr", desktop: "sr", name: "Serbian (Cyrillic)", script: "Cyrillic", plurals: ONE_FEW_OTHER },
  { android: "sv", ios: "sv", desktop: "sv", name: "Swedish", script: "Latin", plurals: ONE_OTHER },
  { android: "tg", ios: "tg", desktop: "tg", name: "Tajik", script: "Cyrillic", plurals: ONE_OTHER },
  { android: "th", ios: "th", desktop: "th", name: "Thai", script: "Thai", plurals: OTHER },
  { android: "tk", ios: "tk", desktop: "tk", name: "Turkmen", script: "Latin", plurals: ONE_OTHER },
  { android: "tr", ios: "tr", desktop: "tr", name: "Turkish", script: "Latin", plurals: ONE_OTHER },
  { android: "uk", ios: "uk", desktop: "uk", name: "Ukrainian", script: "Cyrillic", plurals: ONE_FEW_MANY_OTHER },
  { android: "uz", ios: "uz", desktop: "uz", name: "Uzbek (Latin)", script: "Latin", plurals: ONE_OTHER },
  { android: "vi", ios: "vi", desktop: "vi", name: "Vietnamese", script: "Latin", plurals: OTHER },
  { android: "zh-rCN", ios: "zh-Hans", desktop: "zh", name: "Simplified Chinese", script: "Han", plurals: OTHER },
  { android: "zh-rTW", ios: "zh-Hant", desktop: "zht", name: "Traditional Chinese", script: "Han", plurals: OTHER },
];

// Unicode ranges a language's text must touch at least once when the English has words.
export const SCRIPT_RANGES = {
  Ethiopic: [[0x1200, 0x139f]],
  Bengali: [[0x0980, 0x09ff]],
  Cyrillic: [[0x0400, 0x04ff]],
  Tibetan: [[0x0f00, 0x0fff]],
  Greek: [[0x0370, 0x03ff]],
  Devanagari: [[0x0900, 0x097f]],
  Armenian: [[0x0530, 0x058f]],
  Japanese: [[0x3040, 0x30ff], [0x4e00, 0x9fff]],
  Georgian: [[0x10a0, 0x10ff]],
  Khmer: [[0x1780, 0x17ff]],
  Hangul: [[0xac00, 0xd7af], [0x1100, 0x11ff]],
  Lao: [[0x0e80, 0x0eff]],
  Myanmar: [[0x1000, 0x109f]],
  Sinhala: [[0x0d80, 0x0dff]],
  Thai: [[0x0e00, 0x0e7f]],
  Han: [[0x4e00, 0x9fff]],
};

export function languageByCode(code) {
  return LANGUAGES.find((l) => l.android === code || l.ios === code);
}

// Resolves command-line language arguments (`all`, Android or iOS codes) to table rows.
export function resolveLanguages(args) {
  if (args.length === 0 || args.includes("all")) return LANGUAGES;
  return args.map((code) => {
    const language = languageByCode(code);
    if (!language) throw new Error(`${code} isn't in LANGUAGES (scripts/l10n/languages.mjs)`);
    return language;
  });
}
