# Model Selection — Decisions

## Variant Is The Server-Backed Selector

The mobile composer uses **Variant** for the server-provided model variant keys.
`Default` is a mobile label only and is omitted from prompt requests.

## No Persistent Mobile Default

Model selection is remembered per open Session Chat screen only. Mobile v1 does
not write opencode config defaults or persist a separate mobile preference.

## Model Picker Uses Provider Drilldown

The composer model picker uses a two-step bottom sheet: Provider list, then
models for one Provider. If a Model Selection already exists, the sheet opens at
that Provider's models with the Provider name as the sheet title and a header
back button to the Provider list.
