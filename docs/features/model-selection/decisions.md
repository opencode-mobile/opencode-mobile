# Model Selection — Decisions

## Variant Is The Server-Backed Selector

The mobile composer uses **Variant** for the server-provided model variant keys.
`Default` is a mobile label only and is omitted from prompt requests.

## No Persistent Mobile Default

Model selection is remembered per open Session Chat screen only. Mobile v1 does
not write opencode config defaults or persist a separate mobile preference.
