# Rendered Markdown Decisions

## WebView island instead of React Native

- Date: 2026-06-16
- Decision: Rendered Markdown uses sandboxed local WKWebView / Android WebView
  islands inside native screens.
- Reason: mature markdown, code, and diff rendering techniques require a web
  layout environment, while React Native would add a much larger runtime and
  build surface for the same renderer.

## Conditional rendering

- Date: 2026-06-16
- Decision: plain/simple text stays native; complex block markdown content uses
  the WebView renderer.
- Reason: chat timelines should avoid unnecessary WebView instances for short
  messages while still handling complex content correctly.

## Smooth chat rendering policy

- Date: 2026-06-17
- Decision: Session Chat reserves Rendered Markdown WebViews for block
  markdown, tables, fenced code, and fenced `diff`. Simple inline code, links,
  emphasis, and ordinary multiline text stay native/plain in chat.
- Reason: platform WebViews are expensive inside scrolling timelines; smooth
  chat scrolling is more important than rendering every inline markdown marker
  in the first chat markdown slice.

## Raw markdown remains source text

- Date: 2026-06-16
- Decision: copy, restore, send, and repository state keep raw markdown text.
- Reason: rendered markdown is a display projection and must not alter prompts
  or message history.

## Dedicated diff viewer later

- Date: 2026-06-16
- Decision: first slice supports fenced `diff` blocks in messages; dedicated
  session diff review remains a later feature.
- Reason: chat markdown is the first user-visible need, and the diff viewer can
  reuse the renderer after the component contract is proven.

## Rendered height participates in Follow Latest

- Date: 2026-06-16
- Decision: rendered markdown height changes notify the native Session Chat
  timeline and trigger the existing Follow Latest scroll only while the user is
  still following the bottom.
- Reason: WebView content measures after native rows are first laid out; without
  height-aware scroll identity, the chat can land above the latest message or
  shift messily as markdown finishes rendering.

## Renderer lifecycle optimization

- Date: 2026-06-17
- Decision: native wrappers cache renderer HTML, skip duplicate render payloads,
  coalesce height updates, seed rows from per-message height caches, and ignore
  stale height/link callbacks using renderer IDs.
- Reason: late WebView measurement and unnecessary JavaScript renders made
  scrolling feel uneven even when the final rendered content looked correct.
