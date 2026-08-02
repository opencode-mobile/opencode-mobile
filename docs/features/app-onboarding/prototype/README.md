# PROTOTYPE — App onboarding (classic pager content)

Throwaway webpage for first-install onboarding **content** after structure
verdict **A — Classic pager**.

**Question:** What should the classic pager pages say, and how should “set up
server” sit next to Tailscale guidance?

## Run

```bash
npm run prototype:app-onboarding
```

Open http://localhost:5181/

## Page map

| # | Page | Intent |
| --- | --- | --- |
| 1 | Unofficial client | Native client, WIP; not an official OpenCode product |
| 2 | Connect to servers | Recommend Tailscale; optional CTA → setup guide |
| 3 | Main features | Sessions, chat/abort, permissions, diffs, live stream → **Connect to server** |

### Nested page

**Set up opencode serve** — opened from page 2’s “Need the server first?” CTA.
Keeps short setup steps; one primary button opens
https://opencode.ai/docs/server/. Back returns to the pager.

## Structure verdict

Classic pager won earlier — see [VERDICT.md](./VERDICT.md). This iteration is
content-only inside that structure.

## Not for production

Do not merge the HTML prototype into shipping app code as-is.
