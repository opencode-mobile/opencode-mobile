# Attachment

## Intent

`OpenCodeAttachmentChip` presents a pending device Attachment in a prompt and
lets the user remove it before send. `OpenCodeMessageAttachment` presents the
same content after send as a read-only attachment row in an outgoing message.
Use these components for local image, PDF, and text-like Attachments; project
file references and file-change tools use different components.

## Public API

- Variants: `image`, `pdf`, and `text`.
- Chip states: default and remove-enabled. Pressed is native transient feedback.
- Message states: default read-only presentation.
- Required content: stable id, filename, and semantic variant.
- Optional content: decoded image preview suitable for native display.
- Required chip action: remove, identified by the stable id.

The public APIs accept presentation-safe values only. Domain models, server
DTOs, data URLs, repositories, raw bytes, colors, radii, and typography
overrides are not component inputs. Disabled, loading, selected, focused, and
error states are not applicable in this version because validation completes
before a chip is created and send progress belongs to the composer.

## Token Usage

- Semantic tokens: `surface-raised-base`, `border-weaker-base`, `text-strong`,
  `text-base`, `icon-base`, and `icon-strong-base`.
- Component tokens: reuse `listItem.cornerRadius`; no new component token is
  required.
- Typography roles: `type.bodySecondary` for filenames and `type.caption` for
  the uppercase attachment-kind label.

## Behavior

- Each chip is full width of its parent content. Internals are preview, then a
  stacked filename and uppercase kind label, then a remove control labeled with
  the filename. The filename truncates to a single line.
- Message attachments show an image thumbnail when preview data is available;
  otherwise they show a file card with the same stacked filename and kind.
- Callers own multi-item arrangement. Pending chips and sent message attachments
  stack vertically at 6–8 logical-pixel spacing, each item full width, without
  hiding remove controls or filenames. There is no horizontal scroll strip and
  no fixed chip width.
- The owning feature performs validation, decoding, file opening, and actions.
  Components never execute attached content or decode arbitrary data URLs.
- Accessibility combines filename and kind for the item label. The chip remove
  control is a separate action announced as `Remove <filename>`.

## Platform Notes

- iOS uses SwiftUI `Image` previews and native button/accessibility behavior.
- Android uses Compose image previews and clipped ripple feedback.
- Native image scaling may differ while preserving aspect fill, clipping, and
  the shared semantic variants.

## Review Surfaces

- iOS Component Gallery: Attachment.
- Android Component Gallery: Attachment.
- Both galleries include image, PDF, text, long-filename, multiple-chip, and
  sent-message examples in light and dark themes with live remove actions.
  Pending chips and sent rows stack vertically with section labels.

## Parity And Divergence

No intentional divergence is defined. Track temporary differences in Linear.
