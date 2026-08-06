# PROTOTYPE — App onboarding

Throwaway webpage for [OPE-98](https://linear.app/opencode-mobile/issue/OPE-98/app-onboarding).

**Question:** Does the ticket’s first-install flow feel right — classic pager +
nested server setup + locked unofficial/WIP copy?

## Run

From the repo root:

```bash
npm run prototype:app-onboarding
```

Then open http://localhost:5183/?variant=A

## Verdict

**A — Classic pager** won. See [VERDICT.md](./VERDICT.md).

## Variants (historical)

Same locked OPE-98 copy in every variant; structure differed.

| Key | Name | Structure | Outcome |
| --- | --- | --- | --- |
| A | Classic pager | Ticket-faithful: full-screen slides, dots, Skip / Next → Connect; nested setup from page 2 | **Winner** |
| B | Story scroll | Same three beats in one scroll; Connect always sticky | Rejected |
| C | Sheet over home | Same pager beats as a bottom sheet over home Connection onboarding | Rejected |

Switch with the floating bar, `←` / `→`, or `?variant=` (kept for reference).

### Locked copy

1. **Unofficial** — native client, WIP; not an official OpenCode product
2. **Connect** — recommend Tailscale; **Need the server first?** → nested setup
3. **Features** — sessions / chat / permissions / diffs / live → **Connect to server**

**Nested — Set up opencode serve:** short steps; only CTA **Open server docs** →
https://opencode.ai/docs/server/

## Not for production

Capture on a throwaway branch if kept as primary source. Do not merge the
variant switcher into shipping app code.
