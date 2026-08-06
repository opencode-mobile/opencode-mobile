# Verdict — Initial load indicator

**Winner: Mid-Well Status Stack** (Pass 2 / Pass 3 · variant A)

## Question

What should the initial-load progress indicator look like on Home and Session —
clean, not disturbing, and without accent colors?

## Decision

Use a **centered mid-well status stack**:

1. Neutral indeterminate ring (~32pt), stroke from `text-base` / track from
   `border` / `text-weaker` — **no accent tint**
2. Primary label (`text-base`): `Loading sessions` (Home) /
   `Loading messages` (Session)
3. Weak subline (`text-weaker`): `Checking server…` (Home) /
   `Fetching timeline…` (Session)

Top bar and composer/dock chrome stay visible. Content well is otherwise empty.

## Why this won

- Readable presence without feeling tiny (rejected Pass 1 quiet ring)
- Explicit “still loading” copy so the well doesn’t feel empty/fast (rejected
  hairline-only)
- Literal status, not a metaphor (rejected soft inhale wash)
- Avoids skeleton rails, wellhead track panels, and dock-anchored whispers from
  later passes

## Rejected along the way

| Idea | Why not |
| --- | --- |
| Quiet / tiny ring | Too small |
| Edge hairline | Empty + felt too fast |
| Soft inhale wash | Dynamics OK, metaphor weird |
| Content scaffold bars | Skeleton feel wrong for this case |
| Wellhead progress panel | Worst of Pass 2 |
| Soft status capsule / dock whisper / multi-model Pass 4 set | Superseded by this selection |

## Next (implementation)

Fold into a shared design-system loading pattern (or feature-local if the
contract stays screen-specific) on iOS + Android, replacing the tinted system
`ProgressView` / `CircularProgressIndicator` on Home probing/loading and Session
timeline loading.
