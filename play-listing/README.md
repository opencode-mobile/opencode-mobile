# Play Store listing assets

Upload kit for Play Console → **OpenCode Mobile — Unofficial**.

| File | Play Console slot |
| --- | --- |
| `icon-512.png` | High-res icon (512×512) |
| `feature-1024x500.png` | Feature graphic |

Phone screenshots (final): [`../test-screenshots/android_1_*.jpg`](../test-screenshots/)  
App Store screenshots (final): [`../test-screenshots/ios_1_*.jpg`](../test-screenshots/)

## Store icon

The listing icon is the flattened “d” from
[`app-icon/source.svg`](../app-icon/source.svg). Upload
[`../app-icon/store/google-play-512.png`](../app-icon/store/google-play-512.png)
(this folder’s `icon-512.png` is a copy). Regenerate with:

```bash
npm run app-icon:sync-store
```

On-device launcher icons stay on `npm run app-icon:sync` — see
[`docs/design-system/app-icon.md`](../docs/design-system/app-icon.md) and
[`app-icon/store/README.md`](../app-icon/store/README.md).
