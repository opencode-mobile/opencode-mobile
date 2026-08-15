# OPE-172 Faster protocol probe on resume

Parent: [Server Connection](plan.md)
Linear: [OPE-172](https://linear.app/opencode-mobile/issue/OPE-172/faster-protocol-probe-on-resume)
Related: [OPE-165](../foreground-event-stream/ope-165-plan.md)

## Goal

After inactivity, Session Chat reaches **live** without waiting on dual-health.
The foreground event stream opens on a protocol hint. Detect continues in the
background with a short budget.

## In scope

1. Persist last-known protocol on the Connection Profile.
2. Open SSE on cached / persisted protocol, or v1, without awaiting detect.
3. Bound dual-health (short connect and request; overlap both GETs; classify
   unchanged).
4. One in-flight probe. Android must not hold the connection lock across HTTP.
   Home `probeServer` joins that wait.
5. Do not re-probe on every SSE retry after a miss.
6. Android SSE requires 2xx before **live**.

## Out of scope

- Cutting iOS SSE connect below Android’s 10s (measure Tailscale first).
- Home re-probe on every foreground (OPE-165).
- Changing live / connecting / offline labels.
- Defaulting an unresolved probe to v2.

## Expected result

- Warm or persisted protocol: SSE GET starts immediately.
- v1 still wins when `/global/health` is healthy.
- Failed implicit probe returns v1 once per connection generation.
- Composer still waits for SSE 2xx.
