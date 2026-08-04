# Decisions: Diagnostic Logs

## Time window is the product rule

The shareable window is the last **5 minutes**. Eviction is primarily
time-based on each append and on export snapshot. Users and support think in
minutes, not megabytes.

## Soft safety cap is entry-count, silent

Keep a silent soft cap of **2,000 entries**, dropping oldest first. Matches the
TextMate Logbook pattern of a testable count bound (`maxPersistedEntries = 500`)
at a higher ceiling so normal redacted traffic in five minutes should not hit
it. A byte cap is deferred unless entry sizes grow. Export metadata should note
when the soft cap truncated inside the time window.

## Export format is JSONL only

v1 shares a single `.jsonl` file via the system share sheet. One structured
format is enough for support triage and aligns with TextMate’s JSONL journaling.
A plain `.txt` dual path is deferred.

## Clear buffer is in v1

Developer Tools includes Clear next to Share, with confirmation (same need as
TextMate Logbook: wipe after share or before a clean repro). Accidental clear
risk is accepted and mitigated by confirmation copy.

## Dual-write facade, not platform-log scrape

Instrumentation goes through an app logging facade that writes to the platform
sink and the diagnostic buffer. Scraping logcat / `os_log` is out of scope and
would fight redaction and parity.

## Keep TextMate Logbook separate

TextMate Logbook remains fence-highlighting metrics only. Diagnostic Logs is
the general runtime diagnostics surface. Optional later: a TextMate-specific
share action without merging the stores.

## Process-lifetime only for v1

The buffer is in-process. No disk persistence across process death, crash
upload, or remote telemetry in this slice.
