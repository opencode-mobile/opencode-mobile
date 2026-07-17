# File Attachments — Parity Gaps

## Native picker MIME filters

- Expected shared intent: Photos for images; Files/documents for PDF and
  text-like files; unsupported binaries rejected after selection with shared
  copy.
- Current iOS state: PhotosPicker is image-only; document importer uses UTTypes
  for PDF/text-like files.
- Current Android state: Photo Picker for images; document provider uses `*/*`
  so application/json and other text-like MIME types reach central validation.
- Affected area: behavior / picker UX.
- Reason: Android document MIME filters cannot reliably enumerate every
  text-like provider type without excluding accepted content.
- Owner / next action: keep post-selection validation as the source of truth.
- Status: intentional.

Image thumbnail generation is bounded on both platforms; native decoder APIs
may still differ while preserving shared presentation variants.
