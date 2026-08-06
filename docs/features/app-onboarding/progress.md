# Progress: App Onboarding

## Shared Planning

- [x] Plan written (`plan.md`).
- [x] Decisions recorded (`decisions.md`).
- [x] UI prototype verdict: **A — Classic pager**
  ([`prototype/VERDICT.md`](./prototype/VERDICT.md)).
- [x] Native implementation (iOS + Android).

## Design-System

- [x] Primary CTAs use existing `OpenCodeButton`.
- [x] Nested setup uses existing `OpenCodeTopBar` back leading.
- [x] Pager chrome, illustration area, feature rows, and setup steps are
  feature-local for v1.

## iOS

- [x] `AppOnboardingPreferences` (UserDefaults; Clear Data resets).
- [x] Root gate in `AppRootViewModel` / `AppRootView` (`appOnboarding` state).
- [x] Classic pager (3 pages) + nested setup guide (`AppOnboardingView`).
- [x] Skip → home Connection onboarding; Connect → Connection setup.
- [x] Open server docs in system browser.

## Android

- [x] `AppOnboardingPreferences` (SharedPreferences; Clear Data resets).
- [x] Root gate in `AppRootViewModel` / `MainNavigation` (`AppOnboarding` state).
- [x] Classic pager (3 pages) + nested setup guide (`AppOnboardingScreen`).
- [x] Skip → home Connection onboarding; Connect → Connection setup.
- [x] Open server docs via `ACTION_VIEW`.

## Blockers

None.

## Remaining

- Manual device/sim pass on fresh install, Skip, Connect, nested docs, Clear
  Data → relaunch shows pager again.
- Confirm probe failure / Edit connection never re-enter App onboarding
  (gate is cold-start only; those paths stay on home).
