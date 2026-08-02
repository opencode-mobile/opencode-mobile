# Composer Liquid Glass

## User Goal

Session Chat’s composer reads as the same floating glass chrome family as the
Session List home dock: Liquid Glass on iOS 26+, Material raised-surface stand-in
on Android, without changing prompt / permission / question behavior.

## In-Scope

- Update `OpenCodeSessionComposer` shell materials to match Floating Dock chrome:
  - iOS 26+: `glassEffect` + `surface-raised-base` underlay (opacity `0.92`)
  - iOS &lt; 26: `.regularMaterial` fallback
  - Android: raised `Surface` / fill alpha `0.95` with light tonal + shadow
    elevation (no system Liquid Glass)
- Keep one shared shell across prompt, permission, and question states.
- Preserve semantic outlines: neutral for prompt/question; warning for pending
  permission; error after failed permission response.
- Keep existing composer geometry unless a listed alignment decision lands:
  content padding `12`, corner radius `26`, content-adaptive height, existing
  action rows and transitions.
- Update design-system docs for Session Chat Composer (and Floating Dock /
  divergences if shared chrome is extracted).
- Update Component Gallery + previews so glass / raised materials are reviewable
  in light and dark, including permission outline states.
- Align composer-anchored suggestion overlay shell only if the accepted decision
  says so (see Decisions).
- Optional Session Chat bottom fade under the composer only if accepted (see
  Decisions); otherwise leave timeline fade/outsets unchanged.
- iOS and Android Feature Slice in one pass; record intentional material
  divergence the same way as home dock.

## Out-Of-Scope

- Redesigning prompt controls, permission/question content, or Screen Model APIs.
- Perfect pixel-identical glass across iOS and Android.
- App-managed blur stacks or custom shaders on Android.
- Changing keyboard overlap / IME lift behavior (tracked separately; must not
  regress — see OPE-93).
- Home Session List dock changes (already shipped via liquid-glass-home-list).
- Top bar glass / filter-chip work beyond what the composer shell needs.

## Server APIs Involved

None. Visual chrome only.

## Expected Platform Logs

Not applicable. No new API or mutation logging.

## Domain Objects

None new. Existing composer / permission / question presentation state stays the
owner of content; materials are design-system shell concerns.

## Screen Model

Not applicable for chrome materials. No new UI state or actions. Existing
prompt/permission/question content keys and transitions remain.

## Native Navigation Entry Points

- Session Chat (live composer at bottom)
- Component Gallery Session Composer entry (edge-to-edge review)

## Error States

Existing permission warning/error outline semantics must remain legible on glass
/ raised materials. No new error states.

## Permission Request Behavior

Unchanged product behavior. Only shell fill/material changes; allow once / deny
and queue copy stay as today.

## Design-System Components

| Piece | Status |
| --- | --- |
| Floating Dock surface materials | Existing — reuse contract / extract shared chrome helper if needed |
| Session Chat Composer shell | Existing — extend materials; keep prompt/permission/question API |
| Composer Suggestion Overlay shell | Existing — match or defer per decision |
| Bottom Fade | Existing — optional Session Chat use per decision |
| Icon / compact composer buttons | Prefer existing Button; optional iOS 26 glass styles only if needed for parity with dock CTAs |

### Recommended chrome strategy

Do **not** wrap the composer in `OpenCodeFloatingDock` as-is. That component owns
dock-specific outer insets (`16` horizontal, `4` bottom) and always applies
inner `12` padding, which would fight composer gutters, outline overlays, and
Android `AnimatedContent` shell ownership.

Prefer one of:

1. **Extract shared floating chrome** (modifier / small surface helper) used by
   Floating Dock and Session Composer; each caller keeps its own insets and
   content; or
2. **Duplicate the material recipe** inside the composer with a documented
   pointer to Floating Dock tokens (faster, higher drift risk).

Default plan recommendation: (1) shared chrome helper.

## Open Decisions (resolved 2026-08-02)

Accepted answers are recorded in `decisions.md`. Summary:

1. **Outer gutters:** keep composer `12` / bottom `10`.
2. **Suggestion overlay:** match glass / raised shell family.
3. **Session Chat bottom fade:** deferred.
4. **Control chrome:** shell-only.
5. **Chrome strategy:** extract shared floating chrome helper.

## Acceptance (checkable)

- On iOS 26 simulator/device, Session Chat composer shell uses Liquid Glass (not
  opaque `surface-base` fill) in prompt, permission, and question states.
- On iOS &lt; 26, composer uses material fallback, not opaque flat fill.
- On Android, composer uses raised surface stand-in consistent with Floating Dock.
- Permission warning and error outlines remain visible and distinct.
- Suggestion overlay follows the accepted overlay decision.
- Gallery Session Composer scenarios show the new materials on both platforms.
- No Prompt / permission / question API or send/attach/commands behavior change.
- Keyboard lift behavior does not regress relative to the branch baseline.

## Prototype Reference

No throwaway prototype required if materials reuse the Floating Dock recipe.
If control-level glass or bottom fade needs validation, prefer a short gallery
spike over a separate prototype branch.
