# App Onboarding Progress

## Shared Planning

- Status: plan complete from accepted classic-pager prototype.
- Canonical artifacts: `plan.md`, `decisions.md`, `parity-gaps.md`,
  `prototype/` (throwaway HTML content reference).
- Linear: [OPE-98](https://linear.app/opencode-mobile/issue/OPE-98/app-onboarding)
  (single ready-for-agent ticket; Todo).

## Design System

- Reuse `OpenCodeButton` and existing typography/colors.
- Pager chrome and setup steps are feature-local for v1 (no new Component
  Collection primitive yet).

## iOS

- Status: not started.

## Android

- Status: not started.

## Blockers

- None for planning.

## Remaining Work

- Implement App onboarding gate + pager + nested setup on iOS and Android
  ([OPE-98](https://linear.app/opencode-mobile/issue/OPE-98/app-onboarding)).
- Persist completion flag; reset with Clear Data.
- Wire Connect → existing Connection setup; Skip → home / Connection
  onboarding.
- Open https://opencode.ai/docs/server/ via system browser.
- Device smoke: first launch, skip, connect, docs CTA, clear data re-shows
  pager.
- Branch when starting: `feature/ope-98-app-onboarding`.
