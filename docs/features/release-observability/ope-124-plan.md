# OPE-124 — Product Analytics Taxonomy And Instrumentation

## User Goal

Consented product Analytics covers every product screen and every meaningful
user interaction on both apps, through `OpenCodeReleaseObservability` only.
Tricky signals (perf, SSE internals, markdown timings) stay deferred.

Linear: [OPE-124](https://linear.app/opencode-mobile/issue/OPE-124/product-analytics-taxonomy-and-instrumentation).

## Event model

| Event name (Firebase) | Purpose | Required params |
| --- | --- | --- |
| `product_screen_view` | Product surface became visible | `screen_id`, `presentation`, `trigger` |
| `product_user_action` | Meaningful user interaction | `screen_id`, `action_id` |

Optional params (enums only): `outcome`, `mode`, `change_source`, `client_slash`,
`permission_decision`, `question_response`, `hours`, `theme_preset`,
`has_attachments`, `is_edit`, `error_class`.

Do **not** use Firebase’s reserved `screen_view` event name. Automatic screen
reporting stays disabled.

## Hard bans

No prompts, messages, paths, server URLs, credentials, filter/query text,
session IDs as user IDs, ADID/IDFA. Feature code never imports Firebase.

## In scope

- Shared `screen_id` / `action_id` constants on Android and iOS.
- Seam helpers: `logScreenView` / `logUserAction` → consent-gated `logProductEvent`.
- Instrument all product screens (routes, sheets, dialogs, embedded home states)
  and all meaningful user actions from the accepted taxonomy.
- Unit tests for helpers / param shaping.

## Out of scope

- Developer Tools (gallery, TextMate languages/logbook, diagnostic logs, TextMate toggle).
- Perf / markdown / TextMate stage timings.
- SSE / MessageSync health auto events.
- Auto `message_received` without a user tap.
- Second store privacy form pass (operator; still required before treating
  product-event collection as store-disclosure complete).

## Design-system

No new components. Instrumentation is Screen Model / navigation only.

## Acceptance criteria

- [x] Shared event names and parameters documented (this plan + decisions)
- [x] Domain helpers on both platforms via the observability seam
- [x] Screen Models / navigation emit; views do not import Firebase
- [x] Automatic screen reporting remains disabled
- [x] Product events cannot fire without Analytics consent
- [x] Product screens and actions from the taxonomy are instrumented on both apps
  (`composer_dismiss_suggestions` intentional gap on both — no explicit dismiss)
