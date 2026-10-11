---
name: design-system-component
description: "Design System: use for token or typography changes, reusable component work, or Feature Slice UI dependencies."
---

# Design system

One Design System. SwiftUI on iOS, Material 3 on Android. Same meaning, native
mapping. `docs/design-system/` explains why things exist; it is background, not
a ledger, and native public interfaces and tests win when they disagree with
it.

1. **Define the shared interface** before touching either platform.
   - Token: semantic purpose, level, light/dark behavior, both platform
     mappings.
   - Typography: role meaning and both native mappings.
   - Component: intent, public interface, variants, states, content,
     behavior, accessibility.
2. **Implement both native mappings.** Keep variant and state names aligned
   across platforms. Public APIs stay variant-first and state-aware (see
   `AGENTS.md`).
3. **Add gallery and preview entries** for component work, and for token or
   typography work when the change needs a visual check.
4. **Reconcile differences.** Each observable iOS/Android mismatch is resolved,
   intentional and covered by native code and tests, or recorded as a
   temporary gap on the issue or in the handoff.
5. **Check** builds, lint, tests, galleries, and previews on both platforms and
   report results or blockers.
