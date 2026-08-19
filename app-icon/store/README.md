# Store listing icons

Upload-ready rasters of the flattened “d” mark. Stores cannot apply Liquid
Glass or Android adaptive parallax, so these are opaque squares — no rounded
corners, no drop shadows, no transparency holes.

Regenerate from [`../source.svg`](../source.svg):

```bash
npm run app-icon:sync-store
```

On-device launcher icons stay on `npm run app-icon:sync` and are not rewritten
by the store command.

## Upload these

| Console slot | File | Spec |
| --- | --- | --- |
| **Google Play** → Store listing → App icon | [`google-play-512.png`](./google-play-512.png) | 512×512, 32-bit PNG with alpha, sRGB, ≤1024KB, full square |
| **App Store Connect** promotional / marketing 1024 | [`app-store-1024.png`](./app-store-1024.png) | 1024×1024 PNG, sRGB, **no alpha channel**, full square |

Copies of the same bytes also land at:

- `play-listing/icon-512.png` and `android/app/src/main/ic_launcher-playstore.png` (Play kit + Gradle high-res)
- `app-icon/store-1024.png` (same App Store 1024)

## What the consoles apply for you

Do **not** pre-round corners or bake a shadow into these files.

- Play masks the 512 square with a ~30% corner radius and adds its own shadow.
- Apple applies the squircle (and iOS 26 glass on device from `AppIcon.icon`, not from this PNG).

The iOS **product-page icon** normally comes from the app binary’s 1024 App Icon,
not from a Connect upload. Ship a build that contains the new `AppIcon` so the
listing matches home screen. Use `app-store-1024.png` when Connect asks for a
separate 1024 (promotional art, or a listing slot that accepts a file).

## Not in this kit

- Feature graphic (`play-listing/feature-1024x500.png`) — unchanged; not an app icon.
- Screenshots under `test-screenshots/`.
- [`../interlock.svg`](../interlock.svg) — previous listing mark; not used for these rasters.
