---
name: launch-graphics
description: "Launch graphics: use to make or iterate social posts, banners, or headers that show the apps from store screenshots."
---

# Launch graphics

Marketing images (X / LinkedIn posts, profile headers, link previews) built
from the real app screenshots in the Porcelain palette. Tooling lives in
`scripts/launch-graphics/`; specs are JSON in `scripts/launch-graphics/presets/`.

**Prerequisite.** Screenshots from `npm run store:screenshots`
(`store-screenshots/{ios-6.9,android-phone,ipad-13,android-tab10}/{dark,light}/*.png`,
gitignored). `--shots <dir>` also takes a flat folder of
`<device>_<screen>_<mode>.png` files, such as `.snapshot-evidence/out/`
(`iphone-16-pro-max_02-chat-code_dark.png`). Renders with missing screenshots
print a warning and show empty screens.

1. **Look up options:** `npm run graphics:render -- --list` (add `--json`)
   prints compositions, moments, formats, devices, screens, and presets.
2. **Write or adjust a spec** in `presets/<name>.json`, or override on the
   command line. A minimal spec is `{ "composition": "bottom", "moment": "code" }`;
   everything else has defaults. Per-format tweaks go under
   `formats["<W>x<H>"]` (`text`, `crop`, `devices`).
3. **Render:**

   ```bash
   npm run graphics:render -- --spec x-post-approval --formats 1600x900,1500x500 --modes dark,light
   npm run graphics:render -- --all-presets --formats all
   npm run graphics:render -- --spec x-post-approval --set composition=pair --set copy.headline="Ship from anywhere."
   ```

   Output: `launch-graphics/<preset>-<format>-<mode>.png` at 2× (`--out`,
   `--scale`). Each written path is printed, with layout notes.
4. **Inspect and iterate.** Open the PNGs (downscale before viewing), fix the
   spec, re-render. Check for clipped text, cut-off devices, and copy length.
   For hands-on tweaking, `npm run graphics:editor` opens the same renderer
   with an editor; its Presets section saves back to `presets/`.

**Composition vs moment.** The composition is the layout template: which
devices appear, crop anchors, positions, scale, and the text block. The moment
is content only: the screenshot on each device and the label, headline and
subline. Changing the moment never moves or hides a device. A secondary device
is hidden automatically when only a cut-off sliver would fit (a geometry rule
per composition and format); set `devices.<id>.visible: true` to force it.

**Formats.** Posts: 16:9, 2:1, 3:2, 4:3, 1.91:1, 4:5, 1:1. Banners: 21:9,
3:1 X header, 4:1 LinkedIn banner. Any `WxH` from 300 to 4000 also works. The
X header and LinkedIn banner keep the left quarter free for the profile avatar;
the editor can show that safe area, and it is never exported. Full device and
Triple fall back to another composition on banners, with a note.

Colors come only from `shared/theme-presets/porcelain.json`; fonts are the
bundled IBM Plex packages. Do not commit rendered PNGs or screenshots.
`npm run graphics:test` covers spec validation and layout rules.
