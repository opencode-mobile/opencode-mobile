# Initial Load Indicator — Decisions

## Mid-Well Status Stack is the loading chrome

**Decision:** Home and Session Chat first-content waits use
`OpenCodeStatusStack` (neutral ring + title + subtitle), not accent-tinted
system spinners, blank wells, skeletons, or top hairlines.

**Why:** Prototype passes rejected tiny ring, empty hairline, inhale wash,
scaffold, and wellhead. Mid-Well Status Stack was locked in
`prototype/VERDICT.md`.

## Session Loading shows Status Stack (not blank)

**Decision:** Session timeline `Loading` shows Mid-Well Status Stack even on
the OPE-27 SWR path.

**Why:** Blank Loading avoided a tinted spinner wait for Final enrichment.
Status Stack is the product chrome for the interim until the first published
structure/plain frame; it must not reintroduce a wait for Final markdown.

## Copy is caller-supplied, locked per surface

**Decision:** Component API takes `title` + `subtitle` strings; Home and Session
pass the locked pairs from the verdict. No Home/Session enum on the component.

**Why:** Keeps the Component Collection reusable without API churn.

## AppRoot bootstrap uses Status Stack

**Decision:** AppRoot / Navigation `Loading` uses `OpenCodeStatusStack` with the
same Home cold-start copy (`Loading sessions` / `Checking server…`), not an
ad-hoc tinted system spinner.

**Why:** Cold start often paints disk-first Home immediately, so AppRoot was the
only visible spinner and still used accent blue. One component owns all
first-paint loading chrome.
