// Component group: chat-messages. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool } = Kit;
  const IOS = "Components/Chat/", AND = "ui/components/chat/";
  const plat = (ctx) => ctx?.platform ?? Kit.ctx.platform;
  const na = (msg) => `<div class="cm-na">${esc(msg)}</div>`;
  const DIV = {"outgoing-message":[{"severity":"medium","title":"Text alignment in a wrapped bubble differs"},{"severity":"medium","title":"Spacing between message parts differs"},{"severity":"medium","title":"Attachments and text are in opposite order"},{"severity":"medium","title":"Goal and steering captions use different type and rules"},{"severity":"medium","title":"Docs describe a visible footer and waiting caption that no longer exist"},{"severity":"low","title":"Docs say prompts never collapse, but long text is cut"}],"assistant-message":[{"severity":"medium","title":"A short markdown reply fills the row on iOS and hugs on Android"},{"severity":"low","title":"Docs mention a streaming cursor that is not drawn"}],"rendered-markdown":[{"severity":"medium","title":"Heading level 3 is a different size"},{"severity":"medium","title":"Inline code is a different size"},{"severity":"medium","title":"Link underline color token is ignored on Android"},{"severity":"low","title":"An empty link label falls back only on Android"},{"severity":"low","title":"Syntax roles gallery sample exists only on Android"}],"attachment":[{"severity":"high","title":"A sent image is a thumbnail card on iOS and an inline picture on Android"},{"severity":"medium","title":"Android's waiting-to-start upload has no visible hairline"},{"severity":"medium","title":"Chip geometry differs"},{"severity":"medium","title":"A sent file card hugs on iOS and fills on Android"},{"severity":"low","title":"Custom detail line is iOS-only; a locked chip is Android-only"}],"image-gallery":[{"severity":"high","title":"The +N tile disappears on iOS until its picture loads"},{"severity":"high","title":"A single image is announced differently"},{"severity":"medium","title":"Loading and failed single images have different sizes"},{"severity":"medium","title":"Mosaic width cap is iOS-only"},{"severity":"medium","title":"Tile caption and failure content differ"},{"severity":"low","title":"Android loading uses a shimmer, not the documented pulse"}],"image-viewer":[{"severity":"medium","title":"Dismiss, zoom, and position rules differ"},{"severity":"medium","title":"Share is a bottom icon on iOS and a top text button on Android"},{"severity":"low","title":"Android has the viewer but no gallery entry"}],"message-note":[],"timeline-notice":[{"severity":"medium","title":"Goal rows are a component variant on iOS and a feature row on Android"},{"severity":"medium","title":"Horizontal inset differs"},{"severity":"low","title":"Expanded rows wrap the label only on iOS"}],"truncation-row":[],"message-list":[{"severity":"medium","title":"Scroll-to-end pill hides at a different distance"},{"severity":"low","title":"Gallery harness rows use different text sizes"}]};

  /* Generated picture placeholders. Colors are image content, so they do not follow the theme. */
  const HUES = [["#2f6f8f", "#8fd3e8"], ["#a8693b", "#f3c48d"], ["#6d4e9a", "#c9b2ee"], ["#3b6ea8", "#a9cdf5"], ["#4e8a5b", "#b4e3bf"], ["#a14e70", "#f0b6cd"], ["#7a7a3a", "#dedd98"], ["#4a5a6a", "#b8c6d4"]];
  const pic = (i, w = 400, h = 300) => {
    const [a, b] = HUES[i % HUES.length], u = Math.min(w, h) / 10;
    return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><rect width="${w}" height="${h}" fill="${a}"/><circle cx="${w * .78}" cy="${h * .26}" r="${u * 1.5}" fill="${b}" opacity=".85"/><path d="M0 ${h} L${w * .3} ${h * .5} L${w * .52} ${h * .76} L${w * .72} ${h * .58} L${w} ${h * .86} V${h} Z" fill="${b}" opacity=".5"/><rect x="${u}" y="${u}" width="${w * .36}" height="${u * .7}" rx="${u * .2}" fill="#fff" opacity=".55"/><rect x="${u}" y="${u * 2.1}" width="${w * .22}" height="${u * .5}" rx="${u * .2}" fill="#fff" opacity=".3"/></svg>`;
  };
  const NAMES = ["login-screen.png", "settings.png", "session-list.png", "composer.png", "dark-mode.png", "tablet.png", "error-state.png", "capture-8.png"];
  const items = (n) => Array.from({ length: n }, (_, i) => ({ name: NAMES[i % NAMES.length], state: "ready", hue: i, w: i % 3 === 1 ? 300 : 400, h: i % 3 === 1 ? 400 : 300 }));
  const midTrim = (s, max = 18) => s.length <= max ? s : `${s.slice(0, Math.ceil((max - 1) / 2))}…${s.slice(-Math.floor((max - 1) / 2))}`;

  /* Markdown: the safe subset the shared parser renders, with soft breaks kept as line breaks. */
  const KW = /^(abstract|as|async|await|break|case|catch|class|const|continue|data|def|do|else|enum|export|extends|false|final|for|from|fun|function|if|implements|import|in|interface|is|let|new|object|override|private|protected|public|return|sealed|self|static|struct|super|switch|this|throw|true|try|typealias|val|value|var|void|when|where|while|with|yield|SELECT|FROM|WHERE|TRUE|FALSE|AND|OR|INSERT|UPDATE|DELETE)$/;
  const hl = (code, lang) => {
    const hash = /^(python|bash|yaml|toml|makefile)$/.test(lang), sql = lang === "sql";
    const re = /(\/\/[^\n]*|#[^\n{]*|--[^\n]*)|("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`)|(@\w+)|(\b\d+(?:\.\d+)?[A-Za-z]?\b)|([A-Za-z_][\w]*)/g;
    let out = "", at = 0, m;
    while ((m = re.exec(code))) {
      out += esc(code.slice(at, m.index)); at = re.lastIndex;
      const [t, com, str, attr, num, id] = m; let role = "";
      if (com) { if (com.startsWith("//") && !hash) role = "comment"; else if (com.startsWith("#") && hash) role = "comment"; else if (com.startsWith("--") && sql) role = "comment"; else { re.lastIndex = m.index + 1; out += esc(t[0]); at = re.lastIndex; continue; } }
      else if (str) role = "string"; else if (attr) role = "attribute"; else if (num) role = "number";
      else if (id) {
        const next = code.slice(re.lastIndex).match(/^\s*(\(|<[A-Z])/), prev = code[m.index - 1];
        if (KW.test(id)) role = "keyword"; else if (/^[A-Z][A-Z0-9_]{2,}$/.test(id)) role = "constant"; else if (/^[A-Z]/.test(id)) role = "type"; else if (next) role = "function"; else if (prev === "." || prev === "#") role = "property";
      }
      out += role ? `<span class="r-${role}">${esc(t)}</span>` : esc(t);
    }
    return out + esc(code.slice(at));
  };
  const inline = (s) => {
    const re = /`([^`]+)`|!\[([^\]]*)\]\([^)]*\)|\[([^\]]+)\]\(([^)]+)\)|\*\*(.+?)\*\*|(?<![\w])_(.+?)_(?![\w])|\*(.+?)\*|(https?:\/\/[^\s)]+)/gs;
    let out = "", at = 0, m;
    while ((m = re.exec(s))) {
      out += esc(s.slice(at, m.index)); at = re.lastIndex;
      if (m[1] != null) out += `<span class="md-c">\u202f${esc(m[1])}\u202f</span>`;
      else if (m[2] != null) out += `<span class="md-img">[image: ${esc(m[2])}]</span>`;
      else if (m[3] != null) out += `<span class="md-a" role="link">${inline(m[3])}</span>`;
      else if (m[5] != null) out += `<span class="md-st">${inline(m[5])}</span>`;
      else if (m[6] != null || m[7] != null) out += `<span class="md-em">${inline(m[6] ?? m[7])}</span>`;
      else out += `<span class="md-a" role="link">${esc(m[8])}</span>`;
    }
    return out + esc(s.slice(at));
  };
  const cells = (l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => c.trim());
  const blocks = (src) => {
    const L = src.split("\n"), out = [];
    for (let i = 0; i < L.length;) {
      const l = L[i]; let m;
      if (!l.trim()) { i++; continue; }
      if ((m = l.match(/^```(\w*)/))) {
        const body = []; i++; while (i < L.length && !L[i].startsWith("```")) body.push(L[i++]); i++;
        if (m[1] === "diff") out.push(`<div class="md-diff">${body.map((x) => { const k = /^(\+\+\+|---|@@)/.test(x) ? "hdr" : x[0] === "+" ? "add" : x[0] === "-" ? "del" : ""; return `<span class="md-dl ${k}">${esc(x) || " "}</span>`; }).join("")}</div>`);
        else out.push(`<div class="md-code"><pre>${hl(body.join("\n"), m[1])}</pre></div>`);
        continue;
      }
      if ((m = l.match(/^(#{1,6})\s+(.*)/))) { const n = Math.min(m[1].length, 3); out.push(`<div class="md-h md-h${n}" role="heading" aria-level="${m[1].length}">${inline(m[2])}</div>`); i++; continue; }
      if (/^(-{3,}|\*{3,})\s*$/.test(l)) { out.push('<div class="md-hr"></div>'); i++; continue; }
      if (l.startsWith(">")) { const q = []; while (i < L.length && L[i].startsWith(">")) q.push(L[i++].replace(/^>\s?/, "")); out.push(`<div class="md-q">${inline(q.join("\n"))}</div>`); continue; }
      if (/^\s*[-*]\s+/.test(l)) { let h = ""; while (i < L.length && /^\s*[-*]\s+/.test(L[i])) h += `<span class="md-mark" aria-hidden="true">•</span><div class="md-li">${inline(L[i++].replace(/^\s*[-*]\s+/, ""))}</div>`; out.push(`<div class="md-list">${h}</div>`); continue; }
      if ((m = l.match(/^(\d+)\.\s+/))) { let n = +m[1], h = ""; while (i < L.length && /^\d+\.\s+/.test(L[i])) h += `<span class="md-mark" aria-hidden="true">${n++}.</span><div class="md-li">${inline(L[i++].replace(/^\d+\.\s+/, ""))}</div>`; out.push(`<div class="md-list">${h}</div>`); continue; }
      if (l.includes("|") && /^\s*\|?[\s:|-]+\|?\s*$/.test(L[i + 1] ?? "") && (L[i + 1] ?? "").includes("-")) {
        const head = cells(l); i += 2; const rows = []; while (i < L.length && L[i].includes("|")) rows.push(cells(L[i++]));
        out.push(`<div class="md-table"><table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${head.map((_, x) => `<td>${inline(r[x] ?? "")}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`);
        continue;
      }
      const p = []; while (i < L.length && L[i].trim() && !/^(```|#{1,6}\s|>|\s*[-*]\s+|\d+\.\s+|-{3,}\s*$)/.test(L[i])) p.push(L[i++]);
      if (!p.length) p.push(L[i++]);
      out.push(`<div class="md-p">${inline(p.join("\n"))}</div>`);
    }
    return out.join("");
  };
  const md = (src, variant = "assistantMessage") => `<div class="md${variant === "standalone" ? " is-standalone" : ""}">${blocks(src)}</div>`;

  const MD = {
    assistantIos: "### Release checklist\n\nThe session is ready for review. Start with the **native render tree**,\nthen verify the [session status](https://example.com/session-status).\n\n```swift\nlet renderer = NativeMarkdownRenderer(platform: .iOS)\nrenderer.render(document)\n```\n\n- Preserve raw markdown for copy and restore.\n- Keep `http` and `https` links native-owned.",
    assistantAndroid: "### Release checklist\n\nThe session is ready for review. Start with the **native render tree**,\nthen verify the [session status](https://example.com/session-status).\n\n- Preserve raw markdown for copy and restore.\n- Keep `http` and `https` links native-owned.\n\n```kotlin\nval renderer = NativeMarkdownRenderer(platform = Platform.Android)\nrenderer.render(document)\n```",
    request: "Please review the markdown migration before merging. The response should\ncall out any regression in `SessionChat`, confirm that `diff` blocks keep\nhorizontal scrolling, and include the exact test command to rerun after\nresolving comments. This intentionally long formatted paragraph verifies\nthat an outgoing inline-markdown message remains fully readable.",
    code: '```typescript\nexport function renderSessionMarkdown(source: string): RenderTree {\n  return parseMarkdown(source, { highlight: "textmate", allowHtml: false })\n}\n```',
    syntax: "```typescript\nclass HttpClient {\n  #baseUrl;\n\n  request(path: string) {\n    const url = `${this.#baseUrl}${path}`;\n    return url ?? \"\";\n  }\n}\n```\n\n```kotlin\n@JvmInline\nvalue class Retry(val count: Int)\n\nconst val MAX_RETRIES = 3\n\nfun backoff(retry: Retry): Long = retry.count * 250L + MAX_RETRIES // ms\n```",
    languages: "### Syntax language sweep\n\n```kotlin\n@Serializable\ndata class Session(val id: String, val active: Boolean) {\n    fun label(max: Int = 12): String = if (active) id.take(max + 1) else \"idle\"\n}\n```\n\n```swift\nlet status = sessions.first(where: \\.isActive)\n```\n\n```typescript\nconst retry = async <T>(work: () => Promise<T>): Promise<T> => work()\n```\n\n```python\ndef summarize(messages: list[str]) -> str:\n    return \"\\n\".join(messages)\n```\n\n```json\n{ \"renderer\": \"native\", \"network\": false }\n```\n\n```bash\n./gradlew :app:assembleDebug\n```\n\n```yaml\nmarkdown:\n  renderer: native\n  streaming: debounce\n```\n\n```java\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hello\");\n    }\n}\n```\n\n```csharp\npublic sealed class SessionService {\n    public string Label(string id) => id;\n}\n```\n\n```sql\nSELECT id, label FROM sessions WHERE active = TRUE;\n```\n\n```toml\n[markdown]\nrenderer = \"native\"\n```\n\n```makefile\n.PHONY: build test\nbuild:\n\t@npm run build\ntest:\n\t@npm test\n```",
    rich: "## Review decision\n\n> Ship the native renderer only when the message stays readable on a\n> narrow screen and links remain platform-owned.\n\n1. Verify **assistant** and _outgoing_ variants.\n2. Check `diff` colors in light and dark themes.\n3. Open the [release checklist](https://example.com/release-checklist).\n\n![Mobile session screenshot](https://example.com/session.png)\n\n---\n\nThe image is represented as safe alt text; no remote content is loaded.",
    table: "### Verification matrix\n\n| Surface | Expected result | Owner |\n| --- | --- | --- |\n| Session Chat | Native text and code blocks | Mobile |\n| Streaming | No forced scroll after user scrolls up | Mobile |\n| Links | Only HTTPS leaves the app | Platform |",
    diffIos: "```diff\n@@ SessionChatView.swift\n-Text(message.text)\n+OpenCodeRenderedMarkdown(document: message.renderedMarkdown)\n```",
    diffAndroid: "```diff\n@@ SessionChatScreen.kt\n-Text(message.text)\n+OpenCodeRenderedMarkdown(document = message.renderedMarkdown)\n```",
    headings: "# Heading one\n\n## Heading two\n\n### Heading three\n\nBody text with *emphasis*, **strong**, `inline code`, a [link](https://example.com), and https://example.com/bare.",
  };
  const ROLES = [["keyword", "syntax-keyword"], ["tag", "syntax-keyword", "keyword"], ["string", "syntax-string"], ["number", "syntax-number"], ["constant", "syntax-constant"], ["comment", "syntax-comment"], ["type", "syntax-type"], ["function", "syntax-function"], ["property", "syntax-property"], ["attribute", "syntax-attribute"], ["inserted", "diff-add"], ["deleted", "diff-delete"], ["warning", "syntax-diff-unknown"], ["critical", "syntax-critical"], ["variable", "plain code text", ""], ["operator", "plain code text", ""], ["punctuation", "plain code text", ""], ["plain", "plain code text", ""]];
  const rolesBlock = () => `<div class="md"><div class="md-code"><pre>${ROLES.map(([n, t, c]) => `<span class="${c === "" ? "" : `r-${c ?? n}`}">${n.padEnd(12)}${t}</span>`).join("\n")}</pre></div></div>`;

  /* Attachment */
  const KIND = { image: "IMAGE", pdf: "PDF", text: "TEXT", file: "FILE" };
  const prev = (v) => v === "image" ? `<span class="cm-prev is-img">${pic(3, 64, 64)}</span>` : `<span class="cm-prev">${I("description")}</span>`;
  const chip = (p, ctx) => {
    const os = plat(ctx);
    if (p.upload === "starting" && os === "ios") return na("Android only: an upload with no progress report yet.");
    if (p.locked && os === "ios") return na("Android only: enabled = false locks the chip.");
    if (p.detail && os === "android") return na("iOS only: a custom detail line under the name.");
    const pct = p.upload === "progress" ? 42 : null, up = p.upload !== "none";
    const det = up ? (pct == null ? "Uploading…" : `Uploading ${pct}%`) : (p.detail || KIND[p.variant]);
    const lab = `${prev(p.variant)}<span class="cm-lab"><b>${esc(p.filename)}</b><span>${esc(det)}</span></span>`;
    const main = p.open ? `<button class="cm-chip-main press" aria-label="${esc(p.filename)}, ${esc(det)}">${lab}</button>` : `<div class="cm-chip-main">${lab}</div>`;
    const x = `${up ? "Cancel upload of" : "Remove"} ${p.filename}`;
    return `<div class="cm-chip">${main}<button class="cm-x${os === "android" ? " press" : ""}" data-cm-rm aria-label="${esc(x)}"${p.locked ? " disabled" : ""}>${I("close")}</button>${pct != null ? `<span class="cm-hair" style="width:${pct}%"></span>` : ""}</div>`;
  };
  const inlineImage = (w, h, hue, name) => `<div class="cm-inline-img" role="img" aria-label="${esc(name)}" style="width:min(100%,${(220 * w / h).toFixed(1)}px);aspect-ratio:${w}/${h}">${pic(hue, w, h)}</div>`;
  const sent = (p, ctx) => {
    if (p.variant === "image" && plat(ctx) === "android") return inlineImage(p.ratioW, p.ratioH, 3, p.filename);
    return `<div class="cm-sent" role="group" aria-label="${esc(p.filename)}, ${KIND[p.variant]}">${prev(p.variant)}<span class="cm-lab"><b>${esc(p.filename)}</b><span>${KIND[p.variant]}</span></span></div>`;
  };

  /* Image Gallery */
  const tile = (it, i, count, single, os) => {
    const over = !single && count > 4 && i === 3 ? count - 3 : 0;
    const ready = it.state === "ready", loading = it.state === "loading", failed = it.state === "failed";
    let inner = ready ? pic(it.hue, it.w, it.h) : "";
    if (loading) inner = '<span class="cm-load"></span>';
    if (failed && !(os === "android" && over)) {
      const name = esc(os === "ios" ? midTrim(it.name, 22) : it.name);
      inner = os === "ios" ? `<span class="cm-fail">${I("description")}<span>Couldn't load</span><span>${name}</span></span>` : `<span class="cm-fail">${I("description")}<span class="is-name">${name}</span><span>Couldn't load</span></span>`;
    }
    const showOver = over && (ready || os === "android");
    if (showOver) inner += `<span class="cm-more">+${over}</span>`;
    else if (!single && ready) inner += `<span class="cm-cap">${esc(os === "ios" ? midTrim(it.name) : it.name)}</span>`;
    const canOpen = ready || (os === "android" && over > 0);
    const base = single && os === "ios" ? "Image" : `Image ${i + 1} of ${count}`;
    const label = `${base}, ${it.name}${over > 1 ? `, ${over - 1} more` : ""}${loading ? ", loading" : failed ? ", could not load" : ""}`;
    let style = "";
    if (single) {
      if (ready) style = ` style="width:min(100%,${(220 * it.w / it.h).toFixed(1)}px);aspect-ratio:${it.w}/${it.h}"`;
      else if (os === "ios") style = ` style="width:min(100%,${(220 * 4 / 3).toFixed(1)}px);aspect-ratio:4/3"`;
      else style = ` style="width:100%;height:${loading ? 160 : 80}px"`;
    }
    return canOpen ? `<button class="cm-t press" data-cm-open="${i}" aria-label="${esc(label)}"${style}>${inner}</button>` : `<div class="cm-t" role="img" aria-label="${esc(label)}"${style}>${inner}</div>`;
  };
  const galleryItems = (p) => {
    const n = +p.count, list = items(n);
    if (p.tiles === "loading") list.forEach((x) => { x.state = "loading"; });
    if (p.tiles === "failed") list.forEach((x) => { x.state = "failed"; x.name = "missing-capture.png"; });
    if (p.tiles === "mixed" && n >= 3) { list[1].state = "loading"; list[1].name = "still-loading.png"; list[2].state = "failed"; list[2].name = "missing-capture.png"; }
    if (p.tiles === "overflowLoading") list[3].state = "loading";
    if (p.tiles === "overflowFailed") list[3].state = "failed";
    return list;
  };
  const gallery = (list, ctx) => {
    const os = plat(ctx), n = list.length, layout = n >= 4 ? 4 : n;
    return `<div class="cm-g is-${layout}" data-cm-items="${esc(JSON.stringify(list))}">${list.slice(0, layout).map((it, i) => tile(it, i, n, n === 1, os)).join("")}</div>`;
  };

  /* Image Viewer */
  const SHARE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 15V3.5M8 7l4-4 4 4"/><path d="M8 10.5H6.5A1.5 1.5 0 0 0 5 12v7.5A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5H16"/></svg>';
  const vwPage = (it, os) => {
    const fit = `<div class="cm-vw-pic" style="aspect-ratio:${it.w}/${it.h}">${pic(it.hue, it.w, it.h)}</div>`;
    if (it.state === "loading") return '<div class="cm-vw-spin" role="progressbar" aria-label="Loading"></div>';
    if (it.state === "failed") return os === "ios" ? `<div class="cm-vw-note">Couldn't load this image<small>${esc(it.name)}</small>${it.retry ? '<button class="cm-vw-retry" data-cm-retry>Retry</button>' : ""}</div>` : `<div class="cm-vw-note">Couldn't load ${esc(it.name)}</div>`;
    return fit;
  };
  const vwBadge = (it, os) => {
    if (it.state === "previewLoading") return `<div class="cm-vw-badge is-spin" role="status" aria-label="Loading full image"><span class="cm-vw-spin"></span></div>`;
    if (it.state === "previewFailed") return `<div class="cm-vw-badge is-fail">Couldn't load full image<button class="cm-vw-retry" data-cm-retry>Retry</button></div>`;
    void os; return "";
  };
  const canShare = (it) => it.state === "ready";
  const viewer = (list, index, ctx, float = false, share = true) => {
    const os = plat(ctx), it = list[index];
    const pos = list.length > 1 || os === "android" ? `<b data-cm-pos>${index + 1} of ${list.length}</b>` : "";
    const trail = os === "android" && share ? `<button class="cm-vw-btn" data-cm-share style="width:auto;border-radius:20px;padding:0 12px;font-size:calc(14px * var(--ts,1));font-weight:500"${canShare(it) ? "" : " disabled"}>Share</button>` : '<span class="cm-vw-gap"></span>';
    return `<div class="cm-vw${float ? " is-float" : ""}" role="dialog" aria-label="Image viewer" data-cm-vw data-cm-index="${index}" data-cm-share-on="${share ? 1 : 0}" data-cm-items="${esc(JSON.stringify(list))}"><div class="cm-vw-bg"></div><div class="cm-vw-track" style="transform:translateX(${-index * 100}%)">${list.map((x) => `<div class="cm-vw-page">${vwPage(x, os)}</div>`).join("")}</div><div class="cm-vw-badge-host">${vwBadge(it, os)}</div><div class="cm-vw-top"><button class="cm-vw-btn" data-cm-close aria-label="Close">${I("close")}</button><div class="cm-vw-title">${pos}<span data-cm-name>${esc(os === "ios" ? midTrim(it.name, 30) : it.name)}</span></div>${trail}</div>${os === "ios" ? `<div class="cm-vw-bot"><button class="cm-vw-btn" data-cm-share aria-label="Share"${canShare(it) ? "" : " disabled"}><span class="i">${SHARE}</span></button></div>` : ""}<div class="cm-vw-closed"><button data-cm-reopen>Viewer dismissed. Reopen</button></div></div>`;
  };
  const bindViewer = (el, ctx) => {
    if (el.dataset.bound) return; el.dataset.bound = "1";
    const os = plat(ctx), list = JSON.parse(el.dataset.cmItems), track = el.querySelector(".cm-vw-track"), bg = el.querySelector(".cm-vw-bg");
    const top = el.querySelector(".cm-vw-top"), bot = el.querySelector(".cm-vw-bot"), host = el.querySelector(".cm-vw-badge-host");
    const MAX = os === "ios" ? 4 : 5, DOUBLE = 2.5;
    let index = +el.dataset.cmIndex, scale = 1, tx = 0, ty = 0, drag = null, lastTap = 0;
    const picEl = () => el.querySelectorAll(".cm-vw-page")[index]?.querySelector(".cm-vw-pic");
    const applyZoom = () => { const p = picEl(); if (p) p.style.transform = scale === 1 ? "" : `translate(${tx}px,${ty}px) scale(${scale})`; };
    const clamp = () => { const r = el.getBoundingClientRect(), mx = r.width * (scale - 1) / 2, my = r.height * (scale - 1) / 2; tx = Math.max(-mx, Math.min(mx, tx)); ty = Math.max(-my, Math.min(my, ty)); };
    const setZoom = (s) => { scale = Math.max(1, Math.min(MAX, s)); if (scale === 1) { tx = 0; ty = 0; } else clamp(); applyZoom(); };
    const go = (i) => {
      const prevPic = picEl(); if (prevPic) prevPic.style.transform = ""; scale = 1; tx = 0; ty = 0;
      index = Math.max(0, Math.min(list.length - 1, i)); track.style.transform = `translateX(${-index * 100}%)`;
      const it = list[index], pos = el.querySelector("[data-cm-pos]"), name = el.querySelector("[data-cm-name]");
      if (pos) pos.textContent = `${index + 1} of ${list.length}`;
      if (name) name.textContent = os === "ios" ? midTrim(it.name, 30) : it.name;
      el.querySelectorAll("[data-cm-share]").forEach((b) => { b.disabled = !canShare(it); });
      host.innerHTML = vwBadge(it, os);
    };
    const dismiss = () => { if (el.classList.contains("is-float")) el.remove(); else { el.classList.add("is-closed"); reset(); } };
    const reset = () => { track.style.transform = `translateX(${-index * 100}%)`; bg.style.opacity = ""; top.style.opacity = ""; if (bot) bot.style.opacity = ""; };
    el.addEventListener("click", (e) => {
      if (e.target.closest("[data-cm-close]")) dismiss();
      else if (e.target.closest("[data-cm-reopen]")) el.classList.remove("is-closed");
      else if (e.target.closest("[data-cm-retry]")) { const it = list[index]; it.state = it.state === "failed" ? "loading" : "previewLoading"; it.retry = false; el.querySelectorAll(".cm-vw-page")[index].innerHTML = vwPage(it, os); host.innerHTML = vwBadge(it, os); }
    });
    el.addEventListener("wheel", (e) => { if (!e.ctrlKey) return; e.preventDefault(); setZoom(scale * Math.exp(-e.deltaY / 120)); }, { passive: false });
    el.addEventListener("pointerdown", (e) => {
      if (e.target.closest("button")) return;
      const now = e.timeStamp;
      if (now - lastTap < 300) { lastTap = 0; setZoom(scale > 1 ? 1 : DOUBLE); return; }
      lastTap = now; drag = { x: e.clientX, y: e.clientY, t: now, tx, ty, axis: null, py: e.clientY, pt: now, v: 0 }; el.setPointerCapture(e.pointerId);
    });
    el.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (scale > 1) { el.classList.add("is-drag"); tx = drag.tx + dx; ty = drag.ty + dy; clamp(); applyZoom(); return; }
      if (!drag.axis) { if (Math.hypot(dx, dy) < 6) return; drag.axis = dy > 0 && dy > Math.abs(dx) ? "y" : "x"; el.classList.add("is-drag"); }
      if (e.timeStamp > drag.pt) { drag.v = (e.clientY - drag.py) / (e.timeStamp - drag.pt) * 1000; drag.py = e.clientY; drag.pt = e.timeStamp; }
      if (drag.axis === "x") { const edge = (index === 0 && dx > 0) || (index === list.length - 1 && dx < 0) ? .35 : 1; track.style.transform = `translateX(calc(${-index * 100}% + ${dx * edge}px))`; }
      else {
        const off = Math.max(0, dy); track.style.transform = `translate(${-index * 100}%, ${off}px)`;
        bg.style.opacity = os === "ios" ? String(1 - Math.min(off / 360, .6)) : String(1 - Math.min(off / (el.clientHeight || 1), 1));
        top.style.opacity = os === "ios" && off > 0 ? "0" : ""; if (bot) bot.style.opacity = off > 0 ? "0" : "";
      }
    });
    const end = (e) => {
      if (!drag) return; const d = drag; drag = null; el.classList.remove("is-drag");
      if (scale > 1 || !d.axis) return;
      const dx = e.clientX - d.x, dy = e.clientY - d.y;
      if (d.axis === "x") { const w = el.clientWidth || 1; go(Math.abs(dx) > w * .25 ? index + (dx < 0 ? 1 : -1) : index); return; }
      const out = os === "ios" ? dy > 120 || d.v > 800 : dy > el.clientHeight * .18;
      if (out) dismiss(); else reset();
    };
    el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
  };

  /* Outgoing Message */
  const TOKEN = /\[\/[^\]]+\]/g;
  const tokenText = (s) => { let out = "", at = 0; for (const m of s.matchAll(TOKEN)) { out += esc(s.slice(at, m.index)) + `<span class="cm-token">${esc(m[0])}</span>`; at = m.index + m[0].length; } return out + esc(s.slice(at)); };
  const SHORT = "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.";
  const LONG = "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it. Keep the answer practical and note where pricing depends on the booking channel or destination. Then summarize the recommendation in one concise sentence for a mobile chat user.";
  const truncation = (shown, total, pressed) => `<button class="c-trunc press${pressed ? " is-pressed" : ""}" aria-label="Show full message, ${shown.toLocaleString("en-US")} of ${total.toLocaleString("en-US")} characters shown"><span class="c-trunc-t"><b>Show full message</b><span>${shown.toLocaleString("en-US")} of ${total.toLocaleString("en-US")} characters shown</span></span>${I("expand_content")}</button>`;
  const outgoing = (p, ctx) => {
    const os = plat(ctx), pending = p.steering === "pending", dim = pending ? " cm-out-dim" : "";
    const bubble = (inner, extra = "") => `<div class="msg-out is-tappable${pending ? " is-pending" : ""}${extra}" role="button" tabindex="0" aria-label="Your message">${inner}</div>`;
    const atts = () => `<div class="cm-att-host${dim}">${Kit.use("attachment", { mode: "sent", variant: "pdf", filename: "requirements.pdf" }, ctx)}${Kit.use("attachment", { mode: "sent", variant: "text", filename: "notes.md" }, ctx)}</div>`;
    let parts = [], fill = false;
    const body = p.text || "";
    switch (p.content) {
      case "markdown": parts = [bubble(md(MD.request, "outgoingMessage"))]; fill = os === "ios"; break;
      case "attachments": parts = [atts()]; break;
      case "textAttachments": parts = os === "ios" ? [atts(), bubble(tokenText(body))] : [bubble(tokenText(body)), atts()]; break;
      case "images": parts = [`<div class="cm-g-host${dim}" style="width:100%">${gallery(items(2), ctx)}</div>`, bubble("Both phones clip the login button. Can you fix it?")]; fill = true; break;
      case "limited": parts = [bubble(`<div class="cm-limited"><span>${tokenText(body)}</span>${truncation(1000, 588638)}</div>`)]; break;
      case "limitedEmpty": parts = [bubble(`<div class="cm-limited">${truncation(0, 588638)}</div>`)]; break;
      default: parts = [bubble(tokenText(body))];
    }
    const steerLabel = p.steering !== "none" && p.showLabel ? `<div class="cm-out-cap" role="heading">↱ Steering</div>` : "";
    const goal = p.goal ? `<div class="cm-out-cap cm-out-head" role="heading">${I("radio_button_checked")}<span>Goal</span></div>` : "";
    const head = os === "ios" ? (goal || steerLabel) : goal + steerLabel;
    const cap = pending ? '<div class="cm-out-cap">Pending · sends at the next step</div>' : "";
    const time = p.time ? `<span class="cm-time" aria-hidden="true">${esc(p.time)}${p.day ? `<small>${esc(p.day)}</small>` : ""}</span>` : "";
    return `<div class="cm-out"${p.time ? " data-cm-reveal" : ""}>${time}<div class="cm-out-col${fill ? " is-fill" : ""}">${head}${parts.join("")}${cap}</div></div>`;
  };
  const revealOffset = (t) => { const travel = Math.max(0, -t) * .9; return travel <= 52 ? travel : 52 + 20 * (1 - Math.exp(-(travel - 52) / 30)); };
  const bindReveal = (el) => {
    if (el.dataset.bound) return; el.dataset.bound = "1";
    const col = el.querySelector(".cm-out-col"), time = el.querySelector(".cm-time"); let d = null;
    const set = (o) => { col.style.transform = o ? `translateX(${-o}px)` : ""; time.style.opacity = String(Math.min(1, Math.max(0, (o - 12) / 40))); };
    el.addEventListener("pointerdown", (e) => { if (!e.target.closest("button")) d = { x: e.clientX, y: e.clientY, on: false, id: e.pointerId }; });
    el.addEventListener("pointermove", (e) => {
      if (!d) return; const dx = e.clientX - d.x, dy = e.clientY - d.y;
      if (!d.on) { if (Math.hypot(dx, dy) <= 8) return; if (Math.abs(dx) > Math.abs(dy) * 1.2 && dx < 0) { d.on = true; el.classList.add("is-dragging"); el.setPointerCapture(d.id); } else { d = null; return; } }
      set(revealOffset(dx));
    });
    const up = () => { if (d?.on) { el.classList.remove("is-dragging"); set(0); } d = null; };
    el.addEventListener("pointerup", up); el.addEventListener("pointercancel", up);
  };

  /* Timeline Notice */
  const MOVED = "/Users/example/project/.worktrees/feature-702-v2-timeline-notices";
  const notice = (p, ctx) => {
    const os = plat(ctx), goal = p.variant === "goal" || p.variant === "goalEnded", ended = p.variant === "goalEnded", val = (p.value || "").trim();
    const open = p.state === "expanded" && !goal, toggles = !goal && !!val && p.toggle;
    const cls = `c-tn${goal ? " is-goal" : ""}${ended ? " is-ended" : ""}${open ? " is-open" : ""}`;
    const inner = `${p.variant === "moved" ? "<span>↳</span>" : ""}${goal ? I("radio_button_checked") : ""}<span class="c-tn-l${val ? "" : " no-value"}">${esc(p.label)}</span>${val ? `<span class="c-tn-v">${esc(val)}</span>` : ""}${ended ? '<span class="c-tn-tag">ended</span>' : ""}`;
    const a11y = [p.label, val, ended ? "ended" : ""].filter(Boolean).join(" ");
    if (toggles) return `<button class="${cls}${os === "android" ? " press" : ""}" data-cm-tn aria-expanded="${open}" aria-label="${esc(a11y)}">${inner}</button>`;
    if (p.variant === "goal") return `<button class="${cls}${os === "android" ? " press" : ""}" aria-label="${esc(a11y)}">${inner}</button>`;
    return `<div class="${cls}" aria-label="${esc(a11y)}">${inner}</div>`;
  };

  /* Message List */
  const LIST = [
    ["user", "Walk me through the session chat list UX we want on mobile."],
    ["assistant", "The timeline should alternate outgoing prompts and incoming replies with comfortable spacing.\nWhen the latest messages are off-screen, a floating scroll-to-end pill appears above the composer area."],
    ["user", "How should the pill behave when I tap it?"],
    ["assistant", "It should animate to the true bottom of the conversation, including bottom padding, without leaving a small manual scroll gap."],
    ["user", "Add a few more medium-length messages so we can stress-test scrolling in the gallery."],
    ["assistant", "Use plain text only in this harness so height changes come from copy length rather than markdown layout.\nMix short acknowledgements with longer paragraphs to mimic a real coding session."],
    ["user", "Good. Keep the outgoing metadata line visible on user messages."],
    ["assistant", "Done. Metadata stays on outgoing cards only."],
    ["user", "Now simulate a longer assistant answer with multiple sentences that wraps across several lines on phone width."],
    ["assistant", "On Android we rely on LazyColumn visibility of a bottom anchor item.\nOn iOS the UIKit timeline host tracks distance from the bottom for pin and pill visibility on the deployment target.\nThis gallery page exists so we can tune that behavior without loading a live session."],
    ["user", "What happens when I scroll up?"],
    ["assistant", "The scroll-to-end pill should appear once the latest messages are no longer visible."],
    ["user", "Perfect. One more outgoing message near the end."],
    ["assistant", "Scroll up now and the pill should appear centered above the bottom edge of the screen."],
    ["user", "Tap the pill to return here."],
    ["assistant", "You should land on this final assistant reply with no extra manual scroll needed."],
  ];
  const RESIZE = "\n\n[gallery-resized]\nThis extra gallery-only paragraph expands the final message so the scroll harness can verify resize behavior. Tap the resize control again to shrink the message back to its original height.";
  const mlRow = ([role, body], ctx) => role === "user"
    ? `<div class="cm-ml-row is-user">${Kit.use("outgoing-message", { text: body, content: "text", steering: "none", goal: false, time: "" }, ctx)}</div>`
    : `<div class="cm-ml-row"><div class="cm-ml-in">${esc(body || "…")}</div></div>`;
  const bindList = (el, ctx) => {
    if (el.dataset.bound) return; el.dataset.bound = "1";
    const os = plat(ctx), sc = el.querySelector(".cm-ml-scroll"), flag = el.querySelector(".cm-ml-flag"), pill = el.querySelector(".cm-ml-pill");
    const msgs = el.dataset.empty ? [] : LIST.map((m) => [...m]); const follow = el.dataset.follow === "1";
    const PIN = os === "ios" ? 8 : 32, HIDE = os === "ios" ? 32 : 16;
    let live = 0, showing = false, idle = 0;
    const dist = () => Math.max(0, sc.scrollHeight - sc.clientHeight - sc.scrollTop);
    const update = () => {
      const d = dist(), reveal = Math.max(120, sc.clientHeight * .2), met = d <= PIN;
      showing = msgs.length > 0 && d > (showing ? reveal - HIDE : reveal);
      flag.textContent = met && follow ? "Auto-follow armed" : met ? "Threshold met" : "Threshold missed";
      flag.classList.toggle("is-met", met); pill.classList.toggle("is-on", showing);
      el.querySelectorAll("[data-cm-ml=rm] button, [data-cm-ml=size] button").forEach((b) => { b.disabled = !msgs.length; });
    };
    const paint = (keep) => { const wasPinned = dist() <= 32; sc.innerHTML = msgs.map((m) => mlRow(m, ctx)).join(""); if (keep && follow && wasPinned) sc.scrollTop = sc.scrollHeight; update(); };
    sc.addEventListener("scroll", () => { pill.classList.remove("is-on"); clearTimeout(idle); idle = setTimeout(update, 140); });
    el.addEventListener("click", (e) => {
      const b = e.target.closest("[data-cm-ml]"); if (!b) { if (e.target.closest(".cm-ml-pill")) sc.scrollTo({ top: sc.scrollHeight, behavior: "smooth" }); return; }
      const act = b.dataset.cmMl;
      if (act === "add") { live++; const role = msgs.at(-1)?.[0] === "user" ? "assistant" : "user"; msgs.push([role, role === "user" ? `Live message ${live}: keep streaming updates coming every five seconds.` : `Live reply ${live}: this simulated assistant message was appended while live messages are enabled.`]); }
      else if (act === "rm") msgs.pop();
      else if (act === "size" && msgs.length) { const m = msgs.at(-1); m[1] = m[1].includes("[gallery-resized]") ? m[1].split("[gallery-resized]")[0].trim() : m[1] + RESIZE; }
      paint(true);
    });
    paint(false);
    sc.scrollTop = el.dataset.at === "top" ? 0 : sc.scrollHeight; update();
  };

  Kit.define([
    { id: "outgoing-message", doc: "outgoing-message", align: "stretch", pad: true,
      params: [sel("content", ["text", "textAttachments", "attachments", "images", "markdown", "limited", "limitedEmpty"]), text("text", SHORT), sel("steering", ["none", "delivered", "pending"]), bool("showLabel", true, "steering label"), bool("goal", false, "goal header"), text("time", "9:05 PM", "sentAt (drag left)"), text("day", "", "day (not today)")],
      states: [["Short", {}], ["Long", { text: LONG }], ["Command token", { text: "[/review] the current branch" }], ["Attachments only", { content: "attachments" }], ["Text and attachments", { content: "textAttachments", text: "Here are the requirements." }], ["Image gallery", { content: "images" }], ["Rendered markdown", { content: "markdown" }], ["Cut text", { content: "limited", text: "Paste of a long build log, cut where the timeline stops showing it." }], ["Cut, nothing shown", { content: "limitedEmpty" }], ["Steering delivered", { steering: "delivered", text: "Use the staging bucket instead." }], ["Steering pending", { steering: "pending", text: "And skip the migration step." }], ["Pending, no label", { steering: "pending", showLabel: false, text: "And skip the migration step." }], ["Goal kickoff", { goal: true, text: "Match the layout in the screenshot" }], ["Time from another day", { text: "Drag this message left", day: "Yesterday" }]],
      sheet: [
        { title: "Length and content", kind: "stack", inset: true, items: [["Short", {}], ["Long", { text: LONG }], ["Command token", { text: "[/review] the current branch" }], ["Rendered markdown", { content: "markdown" }]] },
        { title: "Attachments and images", kind: "stack", inset: true, items: [["Attachments only", { content: "attachments" }], ["Text and attachments", { content: "textAttachments", text: "Here are the requirements." }], ["Image gallery", { content: "images" }]] },
        { title: "Cut text", kind: "stack", inset: true, items: [["Text and truncation row", { content: "limited", text: "Paste of a long build log, cut where the timeline stops showing it." }], ["Nothing shown before the cut", { content: "limitedEmpty" }]] },
        { title: "Steering and header", kind: "stack", inset: true, items: [["Delivered", { steering: "delivered", text: "Use the staging bucket instead." }], ["Pending", { steering: "pending", text: "And skip the migration step." }], ["Pending, no label", { steering: "pending", showLabel: false, text: "And skip the migration step." }], ["Goal kickoff", { goal: true, text: "Match the layout in the screenshot" }]] },
        { title: "Time reveal (drag a message left)", kind: "stack", inset: true, items: [["Today", { text: "Drag this message left" }], ["Another day", { text: "Drag this message left", day: "Yesterday" }]] },
      ],
      tokens: [["outgoingMessage.cornerRadius", 8], ["outgoingMessage.maxWidthFraction", 0.8], ["outgoingMessage.paddingHorizontal", 12], ["outgoingMessage.paddingVertical", 8], ["content spacing", "iOS 4, Android 8"], ["pending opacity", 0.6], ["pending dash", "1 wide, 4 on, 3 off, text-weaker"], ["reveal cap", 72], ["reveal follow", 0.9], ["reveal fade", "12 to 52"], ["fill", "surface-interactive-weak"], ["command token", "syntax-type"]],
      source: { ios: IOS + "OpenCodeOutgoingMessage.swift", android: AND + "OpenCodeOutgoingMessage.kt" },
      divergences: DIV["outgoing-message"], render: outgoing,
      mount: (s) => s.querySelectorAll("[data-cm-reveal]").forEach(bindReveal) },

    { id: "assistant-message", doc: "assistant-message", align: "stretch", pad: true,
      params: [sel("content", ["short", "long", "empty", "markdown", "code"]), sel("presentation", ["bubble", "plain"])],
      states: [["Short", {}], ["Long", { content: "long" }], ["Empty", { content: "empty" }], ["Markdown", { content: "markdown" }], ["Code", { content: "code" }], ["Plain short", { presentation: "plain" }], ["Plain long", { presentation: "plain", content: "long" }], ["Plain markdown", { presentation: "plain", content: "markdown" }]],
      sheet: [
        { title: "Bubble", kind: "stack", inset: true, items: [["Short", {}], ["Long (fills the row)", { content: "long" }], ["Empty", { content: "empty" }], ["Markdown", { content: "markdown" }], ["Code", { content: "code" }]] },
        { title: "Plain (chip timeline)", kind: "stack", inset: true, base: { presentation: "plain" }, items: [["Short", {}], ["Long", { content: "long" }], ["Markdown", { content: "markdown" }]] },
      ],
      tokens: [["assistantMessage.cornerRadius", 8], ["assistantMessage.maxWidth", 720], ["assistantMessage.paddingHorizontal", 12], ["assistantMessage.paddingVertical", 8], ["plain paddingHorizontal", 4], ["fillRowThreshold", 0.8], ["fill", "surface-raised-base"], ["type", "type.body"]],
      source: { ios: IOS + "OpenCodeAssistantMessage.swift", android: AND + "OpenCodeAssistantMessage.kt" },
      divergences: DIV["assistant-message"],
      render: (p, ctx) => {
        const os = plat(ctx), T = { short: "The Alleskoenner is the right class for that parcel.", long: "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options and keep the recommendation practical.", empty: "…" };
        const isMd = p.content === "markdown" || p.content === "code";
        const body = isMd ? md(p.content === "code" ? MD.code : (os === "ios" ? MD.assistantIos : MD.assistantAndroid)) : `<span class="cm-in-text">${esc(T[p.content])}</span>`;
        return `<div class="msg-in cm-in${isMd ? " has-md" : ""}${p.content === "code" ? " is-full" : ""}${p.presentation === "plain" ? " is-plain" : ""}" data-cm-in>${body}</div>`;
      },
      mount: (s) => s.querySelectorAll("[data-cm-in]").forEach((el) => {
        if (el.classList.contains("has-md")) return;
        const row = el.parentElement?.clientWidth ?? 0; el.classList.remove("is-full");
        if (row && el.offsetWidth > row * .8) el.classList.add("is-full");
      }) },

    { id: "rendered-markdown", doc: "rendered-markdown", align: "stretch", pad: true, layout: "scroll",
      params: [sel("sample", ["assistant", "request", "code", "syntax", "languages", "rich", "table", "diff", "headings", "roles"])],
      states: [["Assistant", {}], ["Request", { sample: "request" }], ["Code", { sample: "code" }], ["Syntax roles", { sample: "syntax" }], ["Languages", { sample: "languages" }], ["Rich", { sample: "rich" }], ["Table", { sample: "table" }], ["Diff", { sample: "diff" }], ["Headings and inline", { sample: "headings" }], ["Role to token map", { sample: "roles" }]],
      sheet: [
        { title: "Blocks and inline styles", kind: "stack", inset: true, items: [["Headings and inline", { sample: "headings" }], ["Rich: quote, list, image alt, rule", { sample: "rich" }], ["Assistant", {}], ["Request (outgoing)", { sample: "request" }]] },
        { title: "Code", kind: "stack", inset: true, items: [["Fenced code", { sample: "code" }], ["Syntax roles", { sample: "syntax" }], ["Role to token map", { sample: "roles" }], ["Diff", { sample: "diff" }]] },
        { title: "Table", kind: "stack", inset: true, items: [["Scrolls when wider than the row", { sample: "table" }]] },
        { title: "Language sweep", kind: "stack", inset: true, items: [["Standalone variant", { sample: "languages" }]] },
      ],
      tokens: [["block spacing", "8 in messages, 12 standalone"], ["list row spacing", 4], ["list marker gap", 8], ["quote bar", "3 wide, radius 2"], ["quote text inset", 13], ["code radius", 6], ["code padding", "12 × 10"], ["diff line padding", "12 × 1, min width 320"], ["diff fills", "add .18, delete .16, header .14"], ["table cell", "min 112, padding 10 × 8, border .5"], ["links", "markdown-link-text, bold, underlined"], ["inline code", "markdown-code-text on markdown-code-bg"]],
      source: { ios: IOS + "OpenCodeRenderedMarkdown.swift", android: AND + "OpenCodeRenderedMarkdown.kt" },
      divergences: DIV["rendered-markdown"],
      render: (p, ctx) => {
        const os = plat(ctx);
        if (p.sample === "request") return Kit.use("outgoing-message", { content: "markdown", steering: "none", time: "" }, ctx);
        if (p.sample === "roles") return `<div class="md-card">${rolesBlock()}</div>`;
        const src = { assistant: os === "ios" ? MD.assistantIos : MD.assistantAndroid, code: MD.code, syntax: MD.syntax, languages: MD.languages, rich: MD.rich, table: MD.table, diff: os === "ios" ? MD.diffIos : MD.diffAndroid, headings: MD.headings }[p.sample];
        const v = p.sample === "languages" || p.sample === "diff" ? "standalone" : "assistantMessage";
        const onlyAndroid = p.sample === "syntax" && os === "ios" ? '<div class="cm-na" style="margin-bottom:8px">The Syntax roles sample is in the Android gallery only. The renderer is the same on iOS.</div>' : "";
        return `${onlyAndroid}<div class="md-card">${md(src, v)}</div>`;
      } },

    { id: "attachment", doc: "attachment", align: "stretch", pad: true,
      params: [sel("mode", ["chip", "sent"]), sel("variant", ["image", "pdf", "text", "file"]), text("filename", "session-screenshot.png"), text("detail", ""), sel("upload", ["none", "progress", "starting"]), bool("open", false, "onOpen"), bool("locked", false, "locked"), { key: "ratioW", label: "ratioW", type: "text", default: 640 }, { key: "ratioH", label: "ratioH", type: "text", default: 360 }],
      states: [["Pending image", {}], ["Pending PDF", { variant: "pdf", filename: "product-brief.pdf" }], ["Pending text", { variant: "text", filename: "notes.md" }], ["Pending file", { variant: "file", filename: "build-output.zip" }], ["Long filename", { variant: "text", filename: "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md" }], ["Opens, custom detail", { variant: "text", filename: "Logs · 15 min", detail: "Tap to preview", open: true }], ["Uploading 42%", { variant: "file", filename: "build-output.zip", upload: "progress" }], ["Upload starting", { variant: "file", filename: "recording.mov", upload: "starting" }], ["Locked", { variant: "pdf", filename: "product-brief.pdf", locked: true }], ["Sent image", { mode: "sent" }], ["Sent PDF", { mode: "sent", variant: "pdf", filename: "product-brief.pdf" }], ["Sent text", { mode: "sent", variant: "text", filename: "notes.md" }], ["Sent file", { mode: "sent", variant: "file", filename: "build-output.zip" }]],
      sheet: [
        { title: "Pending chip", kind: "stack", inset: true, items: [["Image", {}], ["PDF", { variant: "pdf", filename: "product-brief.pdf" }], ["Text", { variant: "text", filename: "notes.md" }], ["File", { variant: "file", filename: "build-output.zip" }], ["Long filename", { variant: "text", filename: "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md" }]] },
        { title: "Chip states", kind: "stack", inset: true, items: [["Opens, custom detail", { variant: "text", filename: "Logs · 15 min", detail: "Tap to preview", open: true }], ["Uploading 42%", { variant: "file", filename: "build-output.zip", upload: "progress" }], ["Upload starting", { variant: "file", filename: "recording.mov", upload: "starting" }], ["Locked", { variant: "pdf", filename: "product-brief.pdf", locked: true }]] },
        { title: "Sent in a message", kind: "stack", inset: true, base: { mode: "sent" }, items: [["Image", {}], ["PDF", { variant: "pdf", filename: "product-brief.pdf" }], ["Text", { variant: "text", filename: "notes.md" }], ["File", { variant: "file", filename: "build-output.zip" }]] },
        { title: "Sent image ratios", kind: "stack", inset: true, base: { mode: "sent" }, items: [["16:9", { filename: "16:9.png" }], ["3:4", { filename: "3:4.png", ratioW: 300, ratioH: 400 }], ["Square", { filename: "square.png", ratioW: 400, ratioH: 400 }], ["Panorama", { filename: "panorama.png", ratioW: 1200, ratioH: 300 }], ["Phone screenshot", { filename: "phone-screenshot.png", ratioW: 360, ratioH: 780 }]] },
      ],
      tokens: [["chip radius", 8], ["chip padding", "iOS 10 × 7, Android 6 (no trailing)"], ["chip preview", "iOS 28, Android 36"], ["remove control", "iOS 20, Android 36 with 24 icon"], ["upload hairline", 2], ["sent padding", 8], ["sent gap", 10], ["sent preview", "iOS 52 image, 32 icon; Android 40"], ["sent image cap", 220], ["fill", "surface-raised-base"], ["border", "border-weaker-base"]],
      source: { ios: IOS + "OpenCodeAttachment.swift", android: AND + "OpenCodeAttachment.kt" },
      divergences: DIV.attachment,
      render: (p, ctx) => p.mode === "sent" ? sent(p, ctx) : chip(p, ctx),
      mount: (s) => s.querySelectorAll("[data-cm-rm]").forEach((b) => { if (b.dataset.bound) return; b.dataset.bound = "1"; b.addEventListener("click", () => { const c = b.closest(".cm-chip"); c.style.display = "none"; }); }) },

    { id: "image-gallery", doc: "image-gallery", align: "stretch", pad: true,
      params: [sel("count", ["1", "2", "3", "4", "6", "8"], "3"), sel("tiles", ["loaded", "loading", "failed", "mixed", "overflowLoading", "overflowFailed"])],
      states: [["1 image", { count: "1" }], ["2 images", { count: "2" }], ["3 images", { count: "3" }], ["4 images", { count: "4" }], ["6 images (+3)", { count: "6" }], ["8 images (+5)", { count: "8" }], ["Loading", { count: "2", tiles: "loading" }], ["Single loading", { count: "1", tiles: "loading" }], ["Failed single", { count: "1", tiles: "failed" }], ["Loaded, loading, failed", { count: "3", tiles: "mixed" }], ["Overflow tile loading", { count: "6", tiles: "overflowLoading" }], ["Overflow tile failed", { count: "6", tiles: "overflowFailed" }]],
      sheet: [
        { title: "Layout by count", kind: "stack", inset: true, items: [["1 image (aspect kept, 220 cap)", { count: "1" }], ["2 images", { count: "2" }], ["3 images", { count: "3" }], ["4 images", { count: "4" }], ["6 images (+3)", { count: "6" }], ["8 images (+5)", { count: "8" }]] },
        { title: "Tile states", kind: "stack", inset: true, items: [["Loading", { count: "2", tiles: "loading" }], ["Single loading", { count: "1", tiles: "loading" }], ["Failed single", { count: "1", tiles: "failed" }], ["Loaded, loading, failed", { count: "3", tiles: "mixed" }], ["Overflow tile loading", { count: "6", tiles: "overflowLoading" }], ["Overflow tile failed", { count: "6", tiles: "overflowFailed" }]] },
      ],
      tokens: [["single max height", 220], ["trio height", 230], ["trio ratio", "1.4 : 1"], ["gap", 4], ["mosaic max width", "iOS 420, Android none"], ["tile radius", 8], ["tile border", "border-weaker-base"], ["overflow scrim", "black .55"], ["caption scrim", "iOS black .6, Android black .7"], ["loading", "iOS pulse .9s, Android shimmer 1.1s"]],
      source: { ios: IOS + "OpenCodeImageGallery.swift", android: AND + "OpenCodeImageGallery.kt" },
      divergences: DIV["image-gallery"],
      render: (p, ctx) => gallery(galleryItems(p), ctx),
      mount: (s, ctx) => s.querySelectorAll("[data-cm-open]").forEach((b) => {
        if (b.dataset.bound) return; b.dataset.bound = "1";
        b.addEventListener("click", () => {
          const host = document.getElementById("screen"), list = JSON.parse(b.closest("[data-cm-items]").dataset.cmItems);
          host.querySelectorAll(":scope > .cm-vw").forEach((x) => x.remove());
          host.insertAdjacentHTML("beforeend", viewer(list, +b.dataset.cmOpen, ctx, true, ctx.platform === "ios"));
          bindViewer(host.lastElementChild, ctx);
        });
      }) },

    { id: "image-viewer", doc: "image-gallery", align: "stretch", overlay: true, cellHeight: 400,
      params: [sel("group", ["one", "five", "failed", "loading", "previewLoading", "previewFailed", "failedRetry"], "five")],
      states: [["One image", { group: "one" }], ["5 images at the second", { group: "five" }], ["Group with a failed image", { group: "failed" }], ["Page loading", { group: "loading" }], ["Preview, full picture loading", { group: "previewLoading" }], ["Preview, full picture failed", { group: "previewFailed" }], ["Failed with Retry", { group: "failedRetry" }]],
      tokens: [["dismiss distance", 120], ["dismiss velocity", 800], ["max zoom", 4], ["double-tap zoom", 2.5], ["round button", "44, white .14"], ["close icon", 22], ["share glyph", 18], ["top bar padding", "12 × 8"], ["bottom bar padding", "16 × 12"], ["position label", "type.heading, white"], ["file name", "type.caption, white .7"]],
      source: { ios: IOS + "OpenCodeImageViewer.swift", android: AND + "OpenCodeImageGalleryViewer.kt" },
      divergences: DIV["image-viewer"],
      render: (p, ctx) => {
        const five = items(5), three = items(3);
        const G = {
          one: [items(1), 0], five: [five, 1],
          failed: [[...three.slice(0, 2), { ...three[2], name: "missing-capture.png", state: "failed" }], 2],
          loading: [[{ ...three[0], state: "loading" }], 0],
          previewLoading: [[{ ...three[0], state: "previewLoading" }], 0],
          previewFailed: [[{ ...three[0], state: "previewFailed" }], 0],
          failedRetry: [[{ ...three[0], name: "missing-capture.png", state: "failed", retry: true }], 0],
        }[p.group];
        return viewer(G[0], G[1], ctx);
      },
      mount: (s, ctx) => {
        document.querySelectorAll("#screen > .cm-vw").forEach((x) => x.remove());
        s.querySelectorAll("[data-cm-vw]").forEach((el) => bindViewer(el, ctx));
      } },

    { id: "message-note", align: "stretch", pad: true,
      params: [sel("variant", ["truncated", "interrupted"])],
      states: [["Truncated", {}], ["Interrupted", { variant: "interrupted" }]],
      sheet: [{ title: "Variants", kind: "stack", inset: true, items: [["Truncated", {}], ["Interrupted", { variant: "interrupted" }]] }],
      tokens: [["icon size", 14], ["icon spacing", 6], ["paddingHorizontal", 4], ["paddingVertical", 2], ["color", "text-weak"], ["type", "type.caption"]],
      spec: "## Intent\nA small, persistent, non-interactive note under an assistant reply that ended early. It is muted on purpose: it explains state and is not an error.\n\n## Public API\n- `variant`: `truncated` (the model hit its output limit) or `interrupted` (the user or another client stopped the run). Each variant owns its copy and glyph.\n\n## Behavior\nThe icon and label read as one accessibility element whose label is the variant copy. The note has no press state.",
      source: { ios: IOS + "OpenCodeMessageNote.swift", android: AND + "OpenCodeMessageNote.kt" },
      divergences: DIV["message-note"],
      render: (p) => { const v = { truncated: ["collapse_content", "Response cut off (length limit)"], interrupted: ["stop_circle", "Interrupted"] }[p.variant]; return `<div class="c-note" role="note" aria-label="${v[1]}">${I(v[0], 14)}<span>${v[1]}</span></div>`; } },

    { id: "timeline-notice", align: "stretch", pad: true,
      params: [sel("variant", ["plain", "moved", "goal", "goalEnded"]), text("label", "Agent changed"), text("value", "Build → Plan"), sel("state", ["collapsed", "expanded"]), bool("toggle", true, "onToggle")],
      states: [["Moved", { variant: "moved", label: "Moved to", value: MOVED }], ["Agent", {}], ["Model", { label: "Switched to", value: "Claude Opus 5.5" }], ["Variant", { label: "Switched variant to", value: "high" }], ["Variant default", { label: "Switched variant to Default", value: "" }], ["Instructions", { label: "Instructions updated", value: "AGENTS.md, packages/app/AGENTS.md, ~/.config/opencode/AGENTS.md" }], ["Restart", { label: "Continuing after restart", value: "" }], ["Goal set", { variant: "goal", label: "Goal set", value: "Make the unit tests pass on the session list refactor" }], ["Goal ended", { variant: "goalEnded", label: "Goal set", value: "Get lint to zero warnings in the session list package" }], ["Expanded", { variant: "moved", label: "Moved to", value: MOVED, state: "expanded" }]],
      sheet: [
        { title: "Variants (tap a row with a value)", kind: "stack", inset: true, items: [["Moved", { variant: "moved", label: "Moved to", value: MOVED }], ["Agent", {}], ["Model", { label: "Switched to", value: "Claude Opus 5.5" }], ["Variant", { label: "Switched variant to", value: "high" }], ["Variant default", { label: "Switched variant to Default", value: "" }], ["Instructions", { label: "Instructions updated", value: "AGENTS.md, packages/app/AGENTS.md, ~/.config/opencode/AGENTS.md" }], ["Restart", { label: "Continuing after restart", value: "" }]] },
        { title: "Goal set", kind: "stack", inset: true, items: [["Current goal", { variant: "goal", label: "Goal set", value: "Make the unit tests pass on the session list refactor" }], ["Earlier goal", { variant: "goalEnded", label: "Goal set", value: "Get lint to zero warnings in the session list package" }]] },
        { title: "States", kind: "stack", inset: true, base: { variant: "moved", label: "Moved to", value: MOVED }, items: [["Collapsed", {}], ["Expanded", { state: "expanded" }]] },
      ],
      tokens: [["spacing", 6], ["verticalPadding", 2], ["horizontal inset", "iOS 0, Android 4"], ["goal icon", 14], ["ended opacity", "iOS .6"], ["label", "text-weak"], ["value", "info"], ["type", "type.caption"]],
      spec: "## Intent\nA quiet one-line record of a session-level change in the timeline: moved, agent, model, instructions, or restart. A weak label is followed by the value in the info tint, with no card, fill, or border.\n\n## Public API\n- `variant`: `plain`, `moved` (leads with ↳), `goal`, `goalEnded`. The goal variants are iOS component variants; Android draws the same rows in `SessionChatGoalSetRow`.\n- `label`: required, non-blank.\n- `value`: optional; blank is treated as none.\n- `state`: `collapsed` (one truncated line) or `expanded` (wraps in full).\n- `onToggle`, `onActivate` (goal only), `tag` (ended goal only).\n\n## Behavior\nA row with a value toggles between one line and the full text. The accessibility label always carries the full value.",
      source: { ios: IOS + "OpenCodeTimelineNotice.swift", android: AND + "OpenCodeTimelineNotice.kt" },
      divergences: DIV["timeline-notice"], render: notice,
      mount: (s) => s.querySelectorAll("[data-cm-tn]").forEach((b) => { if (b.dataset.bound) return; b.dataset.bound = "1"; b.addEventListener("click", () => { const on = b.classList.toggle("is-open"); b.setAttribute("aria-expanded", String(on)); }); }) },

    { id: "truncation-row", align: "stretch", pad: true,
      params: [text("shown", "1000"), text("total", "588638"), bool("pressed")],
      states: [["Default", {}], ["Pressed", { pressed: true }], ["Small counts", { shown: "987", total: "1412" }], ["Nothing shown", { shown: "0" }]],
      sheet: [
        { title: "States", kind: "stack", inset: true, items: [["Default", {}], ["Pressed", { pressed: true }]] },
        { title: "Counts", kind: "stack", inset: true, items: [["Large", {}], ["Small", { shown: "987", total: "1412" }], ["Nothing shown", { shown: "0" }]] },
        { title: "Inside an outgoing bubble", kind: "stack", inset: true, items: [["Cut message", { inBubble: true }]] },
      ],
      tokens: [["minHeight", 40], ["paddingHorizontal", 12], ["paddingVertical", 8], ["spacing", 10], ["icon", 16], ["cornerRadius", 8], ["fill", "text-strong at .06"], ["title", "type.button, text-strong"], ["caption", "type.caption, text-weak"], ["press (iOS)", "text-strong wash .14, scale .97, .16s"]],
      spec: "## Intent\nA full-width row at the point where content was cut, inside the content's own surface, that opens the rest.\n\n## Public API\n- `variant`: `fullMessage(shownCharacters, totalCharacters)`, the only variant.\n- `action`: opens the whole text.\n\n## Behavior\nThe title reads \"Show full message\" over a caption with locale-formatted counts. It is one accessibility button with the caption as its value and \"Opens the whole message\" as its hint. iOS presses with a subtle wash and scale; Android uses a clipped ripple.",
      source: { ios: IOS + "OpenCodeTruncationRow.swift", android: AND + "OpenCodeTruncationRow.kt" },
      divergences: DIV["truncation-row"],
      render: (p, ctx) => p.inBubble ? Kit.use("outgoing-message", { content: "limited", text: "Paste of a long build log, cut where the timeline stops showing it.", steering: "none", time: "" }, ctx) : truncation(+p.shown || 0, +p.total || 0, p.pressed) },

    { id: "message-list", doc: "message-list", align: "stretch", overlay: true, cellHeight: 460,
      params: [sel("scroll", ["end", "top"]), bool("autoFollow"), bool("empty")],
      states: [["Pinned to end", {}], ["Auto-follow armed", { autoFollow: true }], ["Scrolled up (pill)", { scroll: "top" }], ["Empty", { empty: true }]],
      tokens: [["horizontal padding", 16], ["top and bottom padding", 12], ["inter-item spacing", 8], ["auto-follow tolerance", 32], ["pinned tolerance", "iOS 8"], ["reveal threshold", "max(120, 20% of viewport)"], ["pill hide margin", "iOS 32, Android 16"], ["pill bottom padding", 12]],
      source: { ios: "UI/Scroll/MessageListTimeline.swift", android: "ui/scroll/MessageListTimeline.kt" },
      divergences: DIV["message-list"],
      render: (p, ctx) => {
        const btn = (act, title) => `<span data-cm-ml="${act}">${Kit.use("button", { variant: "compactText", contrast: "low", title, size: "medium", disabled: false, pressed: false }, ctx)}</span>`;
        return `<div class="cm-ml" data-cm-list data-at="${p.scroll}"${p.autoFollow ? ' data-follow="1"' : ""}${p.empty ? ' data-empty="1"' : ""}><div class="cm-ml-scroll">${p.empty ? "" : LIST.map((m) => mlRow(m, ctx)).join("")}</div><div class="cm-ml-pill">${Kit.use("action-pill", { label: "Scroll to end" }, ctx)}</div><div class="cm-ml-flag">Threshold met</div><div class="cm-ml-ctrl">${btn("add", "+")}${btn("rm", "-")}${btn("size", "/")}</div></div>`;
      },
      mount: (s, ctx) => s.querySelectorAll("[data-cm-list]").forEach((el) => bindList(el, ctx)) },
  ]);
})();
