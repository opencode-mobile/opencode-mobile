# PROTOTYPE — Home activity grouping

Throwaway webpage for home Session List activity layouts.

**Question:** How should the home session list highlight sessions with recent
activity / needing attention?

## Verdict

**A — Time ladder** won. **No pin** in the first cut.

See [VERDICT.md](./VERDICT.md).

## Run

```bash
npm run prototype:home-activity
```

http://localhost:5181/?variant=A

## Variants (historical)

First pass: A ladder / B spotlight / C inbox.  
Second pass: A expansions with pin (B–E). Pin ideas deferred.

Switch with the floating bar, `←` / `→`, or `?variant=`.

## Not for production

Fold only the validated decision into the real home list. Keep this HTML on a
`prototype/…` branch if you want the full set as a primary source.
