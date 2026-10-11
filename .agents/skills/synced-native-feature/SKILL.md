---
name: synced-native-feature
description: "Feature Slice: use when planning or implementing shared product behavior across iOS and Android."
---

# Feature Slice

One shared product contract, separate native implementations. The user's
request and the GitHub issue are the plan; implementation state and temporary
platform gaps go on the issue (or in the handoff when ticket updates are out of
scope), not in `docs/`.

1. **Define the shared behavior.** Align domain terms, Screen Model state and
   actions, copy, errors, and permission behavior where both platforms express
   the same product concept. Navigation and implementation choices stay
   platform-specific. Identify both native entry points, or mark one not
   applicable.
2. **Classify the UI.** Every visual element is an existing Component
   Collection item, a reusable component or variant to add through
   `design-system-component` first, or feature-local UI.
3. **Implement both mappings** through each affected domain adapter,
   repository or service, Screen Model, and screen.
4. **Reconcile differences.** Observable iOS/Android differences are either
   resolved, intentional because of native platform behavior (and expressed in
   code and tests), or recorded as a temporary gap with a next action.
5. **Check.** Run every applicable build, lint, test, or preview check for the
   changed platforms and layers, and report each result or blocker.
