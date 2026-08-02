# App Onboarding Decisions

## Classic pager structure

- Date: 2026-08-02
- Decision: First-install App onboarding uses a classic pager (illustration,
  title, body/list, dots, Skip, Next / Connect), not story scroll, setup
  checklist, or capability flash cards.
- Why: Chosen in UI prototype; familiar mobile pattern; separates education from
  Connection setup.
- Evidence: [`prototype/VERDICT.md`](./prototype/VERDICT.md).

## Three content beats + nested setup

- Date: 2026-08-02
- Decision: Pager pages are (1) unofficial native WIP client, (2) connect /
  Tailscale with **Need the server first?**, (3) main features. Nested setup
  keeps short steps; only CTA opens https://opencode.ai/docs/server/.
- Why: Sets expectations (unofficial, server-required, capabilities) without
  duplicating Connection setup; official docs own full server detail.
- Evidence: content iteration in [`prototype/index.html`](./prototype/index.html).

## Distinct from Connection onboarding

- Date: 2026-08-02
- Decision: App onboarding is a one-time education pager. Home **Connection
  onboarding** remains the empty state when no Connection Profile is saved after
  the pager is completed or skipped.
- Why: Avoid conflating product introduction with the durable “connect to
  server” empty state used after clear data and before first save.
- See: [server-connection](../server-connection/plan.md).

## Completion on Skip or Connect

- Date: 2026-08-02
- Decision: Skip or Connect marks App onboarding completed. Clear Data (or
  equivalent full wipe) resets completion so first-run education can return.
- Why: Users who skip should not be trapped in the pager; wipe restores a clean
  install experience.
- Revisit when: Settings gains an explicit “Show onboarding again” control.

## No community-built wording

- Date: 2026-08-02
- Decision: Page 1 says native client, work in progress, and unofficial — not
  “community-built”.
- Why: Product preference from content prototyping.
