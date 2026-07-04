# Shiki Visual Parity Paths — Layer 6 and Beyond

Plan for closing the **visual** gap between opencode web (Shiki-themed HTML) and
opencode mobile (native `CodeTokenRole` spans). This doc sits above
[tokenizer-layer5-top10-sync-plan.md](./tokenizer-layer5-top10-sync-plan.md),
which closed **semantic role parity** for the top 10 Session Chat languages.

See also:

- [shiki-visual-parity-path-a-plan.md](./shiki-visual-parity-path-a-plan.md) —
  Path A implementation plan (PA0–PA5)
- [shiki-visual-parity-path-a-plan.html](./shiki-visual-parity-path-a-plan.html) —
  visual companion
- [shiki-visual-parity-paths-plan.html](./shiki-visual-parity-paths-plan.html) —
  visual companion
- [web-vs-mobile-highlighter.md](./web-vs-mobile-highlighter.md) — layer-by-layer
  comparison (layers 5–8)
- [parity-gaps.md](./parity-gaps.md) — current highlighter parity status

## Two kinds of “Shiki parity”

Mobile work so far targets **semantic parity**: for a fenced block, each character
range should receive the same `CodeTokenRole` that Shiki would assign after
collapsing TextMate scopes through the checked-in
`opencode-semantic-golden` theme.

That is **not** the same as **visual parity**: what users see in the browser when
Shiki applies the live opencode theme with full scope-specific color rules.

| Kind | What matches | Oracle | User-visible? |
|------|--------------|--------|---------------|
| **Semantic** | `CodeTokenRole` per range | `npm run markdown:highlighter:golden` + KMP golden tests | Partially — roles must map to distinct colors |
| **Visual** | Foreground color per range | Side-by-side web vs mobile screenshots / DOM color audit | Yes — the actual “web feel” |

Top-10 tokenizer gates (layer 5) closed semantic parity for ten `*-basic`
fixtures on Android/Joni and iOS/libonig. Remaining visual richness gaps
concentrate in **layer 6** (scope → role collapse) and **layer 8** (role → theme
token mapping).

## Current pipeline (where color is lost)

```
TextMate scopes (tokenizer)
    → OpenCodeTextMateScopeMapper (layer 6: ~18 CodeTokenRole values)
    → OpenCodeSyntaxHighlighter post-processing (layer 7: shrinking)
    → Render tree CodeSpan list (JSON bridge on iOS)
    → Native renderer role → design-system syntax token (layer 8)
    → SwiftUI / Compose foreground color
```

Web pipeline for comparison:

```
TextMate scopes (vscode-textmate via Shiki)
    → Shiki theme scope selectors (100+ rules in live theme)
    → Themed HTML <span style="color: …">
```

### Semantic oracle already matches mobile's collapse model

`scripts/generate-markdown-highlighter-fixtures.mjs` builds goldens with an
`opencode-semantic-golden` Shiki theme that maps scopes into the same
`CodeTokenRole` buckets mobile uses. Passing a `*-basic` golden gate therefore
proves tokenizer + scope-mapper agreement with Shiki **at the role level**, not
with the production web palette.

### Role → color mapping is intentionally lossy

`docs/design-system/tokens.md` maps 18 roles to roughly ten syntax tokens.
Notable collapses that make mobile look flatter than web:

| `CodeTokenRole` | Mobile syntax token | Typical web treatment |
|-----------------|---------------------|------------------------|
| `Variable` | `text-primary` (plain body color) | Often still tinted or distinct from punctuation |
| `Operator`, `Punctuation`, `Plain` | `text-primary` | Web theme may separate operators from identifiers |
| `Function` | `syntax-primitive` | Web may distinguish builtins vs user functions |
| `Property`, `Constant` | `syntax-constant` | Web may split object keys, enums, and `this` |
| `Number`, `Attribute` | `syntax-property` | Shared peach bucket |

## Path A — Enriched semantic model (recommended first)

**Goal:** Keep the native `CodeTokenRole` render tree. Improve perceived web
parity by refining scope collapse, adding roles only where the design system has
tokens, and auditing role → color mapping.

**Visual outcome:** More tokens are colored; fewer long plain-text runs. Not
every Shiki scope distinction, but much closer to what users expect from
opencode web.

### A1. Role → syntax token audit

See [shiki-visual-parity-path-a-plan.md](./shiki-visual-parity-path-a-plan.md) for
the full PA0–PA4 implementation breakdown. Summary:
- Extract web Shiki foreground colors from devtools for each token range.
- Compare against mobile `CodeSpan` roles and resolved theme colors in the
  Component Gallery code-block variants.
- Produce a gap table: role, current mobile token, proposed token, example
  snippet.

**Likely early wins:**

- Give `Variable` a dedicated syntax token instead of `text-primary` when web
  tints parameters/locals.
- Revisit `Operator` / `Punctuation` — web often gives operators a subtle accent
  rather than body color.
- Split `Number` from `Attribute` if web consistently differs.

**Touches:** `docs/design-system/tokens.md`, theme presets, Android
`CodeColors.colorForRole`, iOS `OpenCodeRenderedMarkdown` role mapping, Component
Gallery previews.

### A2. Refine `OpenCodeTextMateScopeMapper`

- Align matcher order and prefixes with `opencode-semantic-golden` rules in
  `generate-markdown-highlighter-fixtures.mjs` (single source of truth).
- Add matchers only when a new `CodeTokenRole` (or existing role split) is
  justified by the A1 audit **and** has a design-system token.
- Prefer fixing tokenizer layer-5 gaps before adding mapper exceptions.

**Touches:** `OpenCodeTextMate.kt`, golden fixture regeneration, KMP golden tests.

### A3. Expand `CodeTokenRole` sparingly

Add roles only when:

1. The distinction appears repeatedly in the A1 audit.
2. Collapsing into an existing role forces an incorrect color on web.
3. A semantic syntax token exists (or is added through the design-system
   workflow).

**Candidate roles to evaluate (not approved):**

| Candidate | Example scopes | Risk if skipped |
|-----------|----------------|-----------------|
| `Builtin` | `support.function`, `support.class` | Builtins look like user code |
| `Parameter` | `variable.parameter` | Params look like plain text |
| `Namespace` | `entity.name.namespace`, imports | Import paths flatten |

Each new role increases JSON payload size, mapper complexity, and cross-platform
gallery coverage. Do not add roles that map to the same syntax token.

### A4. Continue layer-5 / layer-7 cleanup

- Expand active golden gates beyond top-10 (popular languages, then broad
  `@Ignore` suite when ready).
- Remove layer-7 fixups as oracles prove tokenizer + mapper coverage.
- Retain regression repro fixtures (HttpClient, EventEmitter) as non-golden safety
  nets.

### A5. Validation (Path A)

| Check | Command / location |
|-------|-------------------|
| Semantic goldens unchanged or intentionally updated | `npm run markdown:highlighter:golden` |
| Mapper + tokenizer gates | `./gradlew :markdown-core:testDebugUnitTest`, `iosSimulatorArm64Test` |
| Visual audit | Component Gallery code blocks + manual web side-by-side |
| Design-system regression | Gallery syntax token swatches, light/dark |

### Path A — Non-goals

- Carrying full TextMate scope stacks in the render tree.
- Bundling the live Shiki theme JSON for runtime scope matching.
- Embedding Shiki / WASM / WebView for code blocks.

### Path A — Risks

| Risk | Mitigation |
|------|------------|
| Role proliferation without distinct colors | Require design-system token before new role |
| Mapper drift from golden theme | Share scope rules between fixture generator and `OpenCodeTextMateScopeMapper` |
| iOS JSON bridge size | Keep role count small; spans already dominate payload |

---

## Path B — Scope-carrying render tree + bundled Shiki theme

**Goal:** Match opencode web's highlighting model: preserve TextMate scope
stacks (or an equivalent stable id), apply the **live** Shiki theme's
scope-selector rules at render time, and emit native foreground colors per span.

**Visual outcome:** Near pixel-level syntax color parity with web for any
language the tokenizer tokenizes correctly.

### B1. Extend the render-tree contract

Today each code span carries `start`, `end`, `role: CodeTokenRole`.

Path B adds scope information, for example:

```json
{
  "start": 0,
  "end": 5,
  "role": "Keyword",
  "scopes": ["source.ts", "keyword.control.import.ts"]
}
```

Or a compact `themeKey` resolved in KMP from the scope stack.

**Design constraints:**

- Version the render-tree schema (`OpenCodeMarkdownJson`).
- Keep iOS Swift decodable types in sync.
- Measure JSON size on large Session Chat histories (3000+ line fixtures).

### B2. Bundle the production Shiki theme

- Pin the same theme JSON opencode web uses (not `opencode-semantic-golden`).
- Store under `shared/markdown-core` or `shared/theme-presets` with a manifest.
- Regenerate when web theme changes; document the sync command.

### B3. Port Shiki scope-selector matching in KMP

Implement (or adapt) logic equivalent to Shiki / VS Code theme matching:

- Scope prefix matching (`entity.name.type` vs `entity.name.type.class`)
- Specificity / longest-prefix-wins
- `L:` / `R:` anchor selectors where the web theme uses them
- Fallback to `editor.foreground`

**Touches:** new KMP module or package, e.g. `OpenCodeShikiThemeResolver`.

### B4. Resolve color at highlight or render time

Two sub-options:

| Sub-option | Resolve when | Pros | Cons |
|------------|--------------|------|------|
| **B4a — KMP resolves hex/rgba** | `highlight()` | Single source; iOS bridge carries final colors | Loses semantic tokens; harder dark/light without re-highlight |
| **B4b — KMP resolves theme token id** | `highlight()` | Keeps design-system indirection | Still need token-per-scope mapping table |
| **B4c — Native resolves from scopes** | Swift / Compose render | Theme mode switches cheaply | Duplicate theme engine on both platforms (avoid) |

**Recommendation:** B4a or B4b in KMP only — one theme resolver, platform
renderers apply resolved colors. Re-highlight on theme change if needed.

### B5. Deprecate or narrow layer 6

- `CodeTokenRole` may remain for diagnostics, accessibility, and fallback paths.
- Primary styling comes from scope → theme color.
- `OpenCodeTextMateScopeMapper` becomes optional / fallback when theme resolve
  fails.

### B6. Validation (Path B)

| Check | Description |
|-------|-------------|
| Theme parity tests | Same input → same hex as Shiki CLI or golden HTML export |
| Cross-platform | Identical colors on Android and iOS for fixed theme + snippet |
| Performance | Benchmark `parseFence` with scope stacks on Big Session fixture |
| Bridge size | Compare JSON bytes before/after on 20-message benchmark |

### Path B — Non-goals

- Running `vscode-textmate` or Shiki inside the app process.
- Per-platform theme engines with diverging rules.

### Path B — Risks

| Risk | Mitigation |
|------|------------|
| JSON bridge bloat | Scope intern table; run-length or delta encoding if needed |
| Theme drift vs web | Pin theme version; CI diff against web export |
| Complexity | Only pursue after Path A plateaus or product demands full fidelity |
| Design-system coupling | Document that syntax colors may come from theme JSON, not only `tokens.md` |

---

## Path comparison

| | Path A — Enriched semantic | Path B — Scope + Shiki theme |
|--|---------------------------|------------------------------|
| **Parity target** | Web-like buckets | Web-exact colors |
| **Render tree** | `CodeTokenRole` (unchanged) | Scopes or resolved colors added |
| **Design system** | Extend syntax tokens / role map | Theme JSON becomes source of syntax colors |
| **Effort** | Low–medium | High |
| **Performance** | Minimal change | Larger spans + theme resolve CPU |
| **Best when** | “Good enough” web feel for Session Chat | Product requires IDE-grade fidelity |

## Explicitly out of scope (Path C)

Embedding Shiki via WASM, a JS runtime, or WebView code islands. Conflicts with
native-first architecture in `AGENTS.md`, adds bundle size and threading cost,
and duplicates the TextMate work already invested in KMP.

Revisit only if product direction changes.

## Recommended sequencing

| Phase | Work | Delivers |
|-------|------|----------|
| **Done** | Top-10 layer-5 semantic gates | Correct roles for common langs |
| **A0** | Role → color audit (A1) | Prioritized visual gap list |
| **A1** | Mapper + token mapping fixes | Noticeable gallery / chat improvement |
| **A2** | Broader golden gates + layer-7 prune | Fewer wrong/unstyled blocks |
| **Decision gate** | Compare audit residual vs product bar | Choose stay on A or start B |
| **B0** | Schema + theme pin + KMP resolver spike | Proof on one language |
| **B1** | Full Path B rollout | Web-exact syntax colors |

Do **not** start Path B until A1 audit shows remaining gaps require scope-level
distinctions that cannot be expressed as new semantic roles.

## Related code

| Piece | Path |
|-------|------|
| Scope → role (layer 6) | `shared/markdown-core/.../OpenCodeTextMate.kt` |
| Layer-7 fixups | `shared/markdown-core/.../OpenCodeSyntaxHighlighter.kt` |
| Semantic golden theme | `scripts/generate-markdown-highlighter-fixtures.mjs` |
| Render tree model | `shared/markdown-core/.../OpenCodeMarkdown.kt` |
| Android role → color | `android/.../OpenCodeRenderedMarkdown.kt` |
| iOS role → color | `ios/.../OpenCodeRenderedMarkdown.swift` |
| Syntax tokens | `docs/design-system/tokens.md`, `shared/theme-presets/opencode.json` |
| Web layer context | `docs/features/native-rendered-markdown/web-vs-mobile-highlighter.md` |

## Open questions

1. Does product want **semantic buckets** (Path A ceiling) or **IDE fidelity**
   (Path B) for v1 Session Chat?
2. Should `Variable` stay plain body color or get a syntax token?
3. When web theme changes, who owns mobile sync — manual pin, CI diff, or shared
   preset package?
4. Is theme-mode switching (light/dark) required at span level without
   re-parsing markdown?
