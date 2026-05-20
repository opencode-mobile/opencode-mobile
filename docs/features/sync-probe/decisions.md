# Sync Probe Decisions

## Use health endpoint as probe source of truth

- Date: 2026-05-19
- Decision: define Sync Probe around `GET /global/health` as the required
  connectivity check before treating a server connection as usable.
- Reason: project docs already treat health check as the canonical reachability
  verification and this keeps probe behavior consistent across platforms.
- Revisit when: Connection Profile setup flow introduces additional acceptance
  checks that should be part of the initial probe.
