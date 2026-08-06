# PROTOTYPE — Initial load indicator

Throwaway webpage for a quiet progress indicator on **Home** and **Session**
during the first load (probing / loading messages).

**Question:** What should the initial-load progress indicator look like — clean,
not disturbing, and without accent colors?

## Verdict

**Mid-Well Status Stack** won (Pass 2/3 · A). See [VERDICT.md](./VERDICT.md).

## Run (historical variants)

```bash
npm run prototype:initial-load
```

http://localhost:5184/?variant=A

Pass 4 is still served for reference; the locked decision is Mid-Well Status
Stack from the earlier pass (centered neutral ring + label + weak subline).

## Pass history

### Pass 1 — rejected

| Key | Name | Feedback |
| --- | --- | --- |
| A | Quiet ring | Too tiny |
| B | Edge hairline | Empty; too fast |
| C | Soft inhale | Good dynamics, weird |

### Pass 2 / 3

| Key | Name | Outcome |
| --- | --- | --- |
| A | Mid-Well Status Stack | **Winner** |
| B | Scaffold / Soft Capsule | Dropped (skeleton doubt / superseded) |
| C | Wellhead / Dock Whisper | Dropped (worst / superseded) |

### Pass 4 — multi-model exploration (superseded)

| Key | Name | Model |
| --- | --- | --- |
| A | Hush Field Lift | cursor-grok-4.5-high |
| B | System Datum | gpt-5.6-sol-medium |
| C | Late Upper Caption | composer-2.5-fast |

## Not for production

Fold only the validated decision into native code. Keep this HTML on a
throwaway branch if you want the full set as a primary source.
