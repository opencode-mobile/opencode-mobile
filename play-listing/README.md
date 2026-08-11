# Play Store listing assets

Upload kit for Play Console → **OpenCode Mobile — Unofficial**.

| File | Play Console slot |
| --- | --- |
| `icon-512.png` | High-res icon (512×512) |
| `feature-1024x500.png` | Feature graphic |

Phone screenshots (final): [`../test-screenshots/android_1_*.jpg`](../test-screenshots/)  
App Store screenshots (final): [`../test-screenshots/ios_1_*.jpg`](../test-screenshots/)

## Store icon

The listing icon is the interlocking-frames store mark from
[`app-icon/interlock.svg`](../app-icon/interlock.svg). Regenerate it (and the
Android `ic_launcher-playstore.png`) with:

```bash
npm run app-icon:sync-store
```

On-device launcher icons stay on [`app-icon/source.svg`](../app-icon/source.svg)
— see [`docs/design-system/app-icon.md`](../docs/design-system/app-icon.md).
