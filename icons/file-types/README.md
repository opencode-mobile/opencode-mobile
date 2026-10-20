# File-type icons

Multi-colour file and folder icons for `OpenCodeFileIcon`, vendored from
[opencode](https://github.com/anomalyco/opencode) at the commit recorded in
`maps.json` (`source.commit`).

- `svg/`: the icons opencode's lookup can reach, copied unchanged from
  `packages/ui/src/assets/icons/file-types/`. The artwork comes from the
  [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme)
  (MIT, Philipp Kief); opencode is MIT as well. See `THIRD_PARTY_NOTICES.md`.
- `maps.json`: `fileNames`, `fileExtensions`, `folderNames` and `defaults` from
  `packages/ui/src/components/file-icon.tsx`, plus `icons` (icon name → SVG).

Do not edit these files by hand. Re-import from an opencode checkout, which
also regenerates both apps:

```bash
node scripts/sync-file-type-icons.mjs --import ~/Projects/opencode
```

`npm run file-icons:sync` regenerates the iOS asset catalog, the Android
VectorDrawables and the Swift/Kotlin tables from this folder alone.
