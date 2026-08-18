# Rendered Markdown

> Superseded: runtime implementation moved to
> [`../native-rendered-markdown/plan.md`](../native-rendered-markdown/plan.md).
> This page records the earlier WebView spike.

## User Goal

Read opencode session messages with formatted markdown, syntax-highlighted code
blocks, and fenced diffs without leaving the native mobile chat experience.

## In Scope

- Render user and assistant `TextPart` text through the WebView renderer only
  when the text has complex block markdown structure.
- Keep plain/simple text on native text controls.
- Support headings, paragraphs, emphasis, links, lists, blockquotes, inline
  code, fenced code, tables, and fenced `diff`.
- Use a sandboxed local WebView renderer on iOS and Android.
- Preserve raw markdown for copy, restore, and send behavior.
- Add Component Gallery previews on both platforms.

## Out Of Scope

- Dedicated session diff viewer UI.
- Per-code-block copy buttons.
- Raw HTML, remote images, in-WebView navigation, or network-loaded renderer
  assets.
- React Native integration.

## Server APIs Involved

No new server API. The feature consumes existing `Message.parts` text from the
Message Repository and Live Store slices.

## Domain Objects

- `Message`
- `MessagePart.Text`
- `Rendered Markdown`

## UI States

- Plain native text.
- Rendered markdown.
- Streaming rendered markdown with debounced updates.
- Cached rendered markdown height while rows settle.
- Outgoing collapsed and expanded markdown.
- Empty text falls back to the existing caller placeholder.

## Error States

Renderer-load failures fall back to native raw text so chat remains readable.

## Permission Behavior

Not applicable.

## Design-System Components Needed

- New `Rendered Markdown` component.
- Existing `OpenCodeOutgoingMessage` composes Rendered Markdown for markdown
  user text while keeping native controls.

## Navigation Entry Points

- Android: Session Chat timeline and Component Gallery.
- iOS: Session Chat timeline and Component Gallery.
