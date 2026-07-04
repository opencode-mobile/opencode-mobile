# Path A Implementation Plan — Enriched Semantic Visual Parity

Implementation plan for [Path A](./shiki-visual-parity-paths-plan.md): keep the
`CodeTokenRole` render tree, improve web-like syntax coloring by wiring existing
design-system tokens, refining scope → role mapping, and expanding roles only
when justified.

Parent context:

- Layer 5 (tokenizer) top-10 semantic gates: **done** (Android + iOS).
- Layer 6–8 (mapper + role → color): **this plan**.

See also:

- [shiki-visual-parity-path-a-plan.html](./shiki-visual-parity-path-a-plan.html) —
  visual companion
- [shiki-visual-parity-paths-plan.md](./shiki-visual-parity-paths-plan.md) —
  Path A vs Path B strategy
- [docs/design-system/tokens.md](../../design-system/tokens.md) — syntax role mapping
- [docs/design-system/components/rendered-markdown.md](../../design-system/components/rendered-markdown.md)

## Goal

Users perceive Session Chat code blocks as **web-like**: parameters, locals,
operators, and punctuation use theme syntax colors instead of flat body text,
without carrying full TextMate scope stacks (Path B).

## Success criteria

1. **Visual:** Side-by-side review of the audit corpus shows mobile matches web
   opencode theme buckets for ≥ 90% of token ranges (by character coverage).
2. **Structural:** Top-10 `*-basic` semantic golden gates stay green on Android
   and iOS (role assignment unchanged unless audit + golden update intentionally
   changes it).
3. **Cross-platform:** Android and iOS resolve the same `CodeTokenRole` → syntax
   token mapping for every role.
4. **Theming:** OpenCode default preset and built-in OC-2 resolver both define
   colors for every syntax token used by renderers.
5. **Regression:** HttpClient private-field behavior preserved (`#baseUrl`
   declaration plain; `this.#baseUrl` as `Property`).

## Key finding (reduces Phase 1 scope)

Theme presets already ship unused syntax tokens:

| Token | In `shared/theme-presets/opencode.json` | Path A status |
|-------|----------------------------------------|--------------------------|
| `syntax-variable` | Yes | **Done** — `Variable` → `syntax-variable` |
| `syntax-operator` | Yes | **Done** — `Operator` → `syntax-operator` |
| `syntax-punctuation` | Yes | **Done** — `Punctuation` → `syntax-punctuation` |
| `syntax-object` | Yes | **Deferred** — not referenced until PA2/PA3 audit proves need |

Built-in `ThemeResolver` on Android/iOS now provides fallback values for these
four tokens (preset themes still override resolver output when loaded). Phase 1
was primarily **wiring**, not new token design.

## Non-goals

- Full Shiki / VS Code scope-level colors (Path B).
- New `CodeTokenRole` values without a distinct syntax token.
- Render-tree schema changes.
- Inline backtick syntax highlighting.

---

## Milestones

| ID | Name | Delivers |
|----|------|----------|
| **PA0** | Visual audit corpus + gap table | Prioritized change list |
| **PA1** | Wire existing syntax tokens | Immediate visual uplift |
| **PA2** | Mapper ↔ golden theme alignment | Correct roles where color was wrong |
| **PA3** | Optional role expansion | Only if PA0 proves need |
| **PA4** | Gallery + docs + tests | Reviewable, locked mapping |
| **PA5** | Layer-5 expansion (parallel track) | Fewer unstyled non-top-10 blocks |

---

## PA0 — Visual audit corpus and gap table

**Owner output:** `docs/features/native-rendered-markdown/path-a-visual-audit.md`
(committed artifact; update when mapping changes).

### PA0.1 — Fix audit corpus

Use existing checked-in samples (no new scratch files):

| ID | Source | Languages | Why |
|----|--------|-----------|-----|
| V1 | Top-10 `*-basic` fixtures | JS, TS, Py, Kt, Swift, JSON, YAML, Sh, Rust, Go | Regression anchors |
| V2 | `javascript-request-repro` | JS | Private fields, async, templates |
| V3 | `javascript-http-error-repro` | JS | Classes, types, comments |
| V4 | Component Gallery syntax sweep | Multi | Realistic UI review |
| V5 | `java-basic` / popular-language smoke samples | Java, C# | Common Session Chat langs |

Optional: add 2–3 hand-picked Session Chat exports later; not required to start
PA1.

### PA0.2 — Extract web reference colors

For each corpus snippet:

1. Render in local opencode web (default OpenCode theme, dark + light).
2. Record per-span: text, computed `color`, inferred scope class if visible in
   DOM.
3. Map web color → nearest syntax token using opencode theme preset hex values.

**Tooling options (pick one in implementation):**

| Option | Pros | Cons |
|--------|------|------|
| Manual devtools + spreadsheet | Fast to start | Tedious |
| Extend `scripts/generate-markdown-highlighter-fixtures.mjs` with live theme export | Repeatable | Needs web theme JSON pin |
| One-off Node script using Shiki + opencode theme | Matches web engine | Extra script maintenance |

Recommendation: start **manual** for V1–V3 (small), automate if PA2 needs more
samples.

### PA0.3 — Record mobile state

For each corpus snippet:

1. Run through `OpenCodeSyntaxHighlighter` (Joni on JVM is enough for role audit).
2. List `(start, end, role, resolved_syntax_token, hex)` using platform theme
   resolution.
3. Diff against web table: **role mismatch** vs **role match but color mismatch**.

### PA0.4 — Gap table schema

| Snippet | Text range | Web token | Mobile role | Mobile token | Gap type | Phase |
|---------|------------|-----------|-------------|--------------|----------|-------|
| `typescript-basic` | `fetchUser` | `syntax-variable` | `Function` | `syntax-primitive` | Role | PA2 |
| `typescript-basic` | `count` | `syntax-variable` | `Variable` | `text-primary` | Color only | PA1 |

**Gap types:**

- **Color only** — role correct; fix PA1 mapping.
- **Role** — fix PA2 mapper or PA3 new role.
- **Tokenizer** — layer 5; track in PA5, not this plan.

### PA0 exit gate

- Gap table committed.
- PA1 task list derived: all **color only** rows for `Variable`, `Operator`,
  `Punctuation`.
- PA2/PA3 candidates listed with count estimate.

---

## PA1 — Wire existing syntax tokens (quick win)

**Hypothesis:** Most “flat code” appearance is PA1, not missing roles.

### PA1.1 — Canonical role → token table

Update `docs/design-system/tokens.md` to match opencode web desktop theme
presets:

| `CodeTokenRole` | Syntax token | Notes |
|-----------------|--------------|-------|
| `Variable` | `syntax-variable` | Was `text-primary` |
| `Operator` | `syntax-operator` | Was `text-primary` |
| `Punctuation` | `syntax-punctuation` | Was `text-primary` |
| `Plain` | `text-strong` / body | Unstyled fallback only |
| `Property` | `syntax-constant` | Unchanged (web bucket) |
| `Constant` | `syntax-constant` | Unchanged |
| `Function` | `syntax-primitive` | Unchanged |
| … | (existing rows) | |

**Intentional exception (document in tokens.md):**

- Private field **declarations** (`#baseUrl;`) stay `Plain` via layer-7
  `highlightPrivateFields`, not `syntax-variable`.

Evaluate after PA1 whether `Property` object keys should move to `syntax-object`
(PA0 audit decides; may stay constant bucket).

### PA1.2 — Built-in theme resolver defaults

Add OC-2 derived defaults when preset does not override:

| Token | Light source | Dark source |
|-------|--------------|-------------|
| `syntax-variable` | Align opencode preset `#d1383d` / semantic intent | `#e06c75` |
| `syntax-operator` | `#318795` | `#56b6c2` |
| `syntax-punctuation` | `text-strong` / ink | `text-strong` |
| `syntax-object` | Same as variable or property per audit | TBD in PA0 |

**Files:**

- `android/.../theme/ThemeResolver.kt`
- `ios/.../Theme/ThemeResolver.swift`

### PA1.3 — Android renderer

**File:** `android/.../OpenCodeRenderedMarkdown.kt`

- Extend `CodeColors` with `variable`, `operator`, `punctuation` (and `object`
  if approved).
- Update `codeColors()` to resolve new tokens via `openCodeColor(...)`.
- Update `colorForRole()`:
  - `Variable` → `variable`
  - `Operator` → `operator`
  - `Punctuation` → `punctuation`
  - `Plain` → `plain` (unchanged)

### PA1.4 — iOS renderer

**Files:**

- `ios/.../Theme/OpenCodeTheme.swift` — add `syntaxVariable`, `syntaxOperator`,
  `syntaxPunctuation`, (`syntaxObject`)
- `ios/.../Components/OpenCodeRenderedMarkdown.swift` — update `color(for:)`

### PA1.5 — Tests

| Test | Change |
|------|--------|
| `OpenCodeRenderedMarkdownCodeSpanTests.codeSpanColorsUseWebAlignedSyntaxTokens` | Expect `syntax-variable`, `syntax-operator`, `syntax-punctuation` |
| Android equivalent (add if missing) | Same token assertions |
| HttpClient repro tests | Still pass; `#baseUrl` declaration not `Variable` |

### PA1.6 — Component Gallery

- `ColorGalleryScreen` — add swatches for new syntax tokens.
- `RenderedMarkdownGalleryMocks` syntax sweep — note in gallery for visual review.

### PA1 exit gate

- PA0 **color only** rows for Variable/Operator/Punctuation resolved.
- Top-10 golden tests green.
- HttpClient repro green.
- Gallery side-by-side sign-off (light + dark).

---

## PA2 — Scope mapper alignment

**When:** After PA1, for **role mismatch** rows in audit.

### PA2.1 — Single source of truth for semantic collapse

Today duplicate logic lives in:

- `scripts/generate-markdown-highlighter-fixtures.mjs` (`semanticTheme.tokenColors`)
- `shared/markdown-core/.../OpenCodeTextMate.kt` (`roleMatchers`)

**Target:** Extract shared scope → role rules to one place.

| Approach | Recommendation |
|----------|----------------|
| Generate Kotlin matcher table from JS | Best long-term; one edit regens both |
| KMP `commonMain` manual table + JS imports JSON | Good if generator reads JSON |
| Test-only parity check | Minimum: test that fixture theme scopes map same as Kotlin |

Start with **parity test**; refactor to generated table if drift recurs.

### PA2.2 — Mapper changes (examples, audit-driven)

Only implement rows proven by PA0:

| Scope pattern | Current role | Candidate | Prerequisite |
|---------------|--------------|-----------|--------------|
| `variable.parameter` | `Variable` | `Variable` + PA1 color | PA1 |
| `support.function` vs `entity.name.function` | both `Function` | `Builtin` role? | PA3 + token |
| `meta.object-literal.key` | `Property` | `Property` or `syntax-object` | PA0 |

Each mapper change:

1. Update `OpenCodeTextMateScopeMapper`.
2. Regenerate goldens if semantic roles change:
   `npm run markdown:highlighter:golden`.
3. Run Android + iOS golden suites.

### PA2.3 — Layer-7 shrink

After mapper fix, remove redundant `normalizeWebSyntaxRoles` branches only when
oracle + repro fixtures prove coverage.

### PA2 exit gate

- PA0 **role mismatch** rows either fixed or explicitly deferred with reason.
- No new layer-7 fixups added.

---

## PA3 — Optional `CodeTokenRole` expansion

**Gate:** Do not start until PA0 lists ≥ 3 recurring role mismatches that cannot
map to existing roles with distinct syntax tokens.

### Decision checklist (all must be yes)

1. Distinction appears in ≥ 3 audit snippets across ≥ 2 languages.
2. Collapsing forces wrong color vs web (not just slightly different hue).
3. New syntax token exists or is added via design-system workflow.
4. New role maps to **different** token than parent role.

### Candidates (from parent plan — not approved)

| Role | Scopes | Syntax token candidate |
|------|--------|------------------------|
| `Builtin` | `support.function`, `support.class` | `syntax-primitive` or new |
| `Parameter` | `variable.parameter` | `syntax-variable` (may be PA1 only) |
| `Namespace` | `entity.name.namespace` | `syntax-variable` |

### PA3 implementation steps (per approved role)

1. Add enum value to `CodeTokenRole` in `OpenCodeMarkdown.kt`.
2. Update JSON serialization / iOS `NativeCodeTokenRole` bridge.
3. Add mapper matchers.
4. Regenerate semantic goldens.
5. Wire renderer + gallery + tests.
6. Update `tokens.md`.

---

## PA4 — Documentation, gallery, and visual sign-off

Synchronized Feature Slice across iOS and Android.

| Artifact | Action |
|----------|--------|
| `docs/design-system/tokens.md` | Final role → token table |
| `docs/design-system/components/rendered-markdown.md` | Update token usage section |
| `path-a-visual-audit.md` | Mark resolved gaps |
| `parity-gaps.md` | Update highlighter visual status |
| `progress.md` | Record PA1–PA4 completion |
| `web-vs-mobile-highlighter.md` | Note layer-8 improvements |
| Component Gallery (both platforms) | Syntax sweep + color swatches |
| `docs/design-system/divergences.md` | Only if intentional platform diff |

### Visual sign-off checklist

- [ ] OpenCode preset, light + dark
- [ ] At least one alternate preset (e.g. One Dark)
- [ ] HttpClient snippet: private fields still correct
- [ ] Top-10 gallery sweep
- [ ] Web side-by-side for V1–V3 corpus

---

## PA5 — Layer-5 expansion (parallel track)

Not required for PA1 visual uplift but reduces “unstyled” blocks in less common
languages.

| Step | Work |
|------|------|
| PA5.1 | Promote popular-language `*-basic` gates (Java, C#, SQL, …) |
| PA5.2 | Enable broad `@Ignore` Shiki golden incrementally |
| PA5.3 | Remove remaining layer-7 fixups per language |

Track in [tokenizer-layer5-top10-sync-plan.md](./tokenizer-layer5-top10-sync-plan.md)
or separate language promotion checklist.

---

## Delivery — single PR (PA0–PA4)

**Decision:** Ship full Path A in **one PR**. Milestones PA0–PA4 are work
packages inside that PR, not separate merges. PA5 (broader tokenizer gates)
stays **out of this PR** — track in
[tokenizer-layer5-top10-sync-plan.md](./tokenizer-layer5-top10-sync-plan.md).

### PR scope

| In the PR | Out of the PR |
|-----------|---------------|
| PA0 visual audit artifact + gap resolutions | PA5 popular-language golden promotion |
| PA1 wire `syntax-variable` / `operator` / `punctuation` | Broad `@Ignore` Shiki suite unlock |
| PA2 scope mapper fixes from audit | Path B (scope stacks + live theme) |
| PA3 new roles **only if** audit gate passes | |
| PA4 docs, gallery, tests, sign-off checklist | |

### Work order inside the PR

Execute in this sequence so later steps do not invalidate earlier ones:

1. **PA0** — Run audit on V1–V5 corpus; commit `path-a-visual-audit.md` with gap
   table and resolved decisions (open questions at bottom of this doc).
2. **PA1** — Wire role → syntax token mapping on Android + iOS; ThemeResolver
   defaults; `tokens.md`.
3. **PA2** — Apply mapper changes for **role mismatch** audit rows only;
   regenerate goldens if roles change (`npm run markdown:highlighter:golden`).
4. **PA3** — Add roles only when audit checklist passes; skip section entirely
   if no row qualifies.
5. **Layer-7** — Remove fixups made redundant by PA2 (minimal diff; keep
   HttpClient + docstring repro coverage).
6. **PA4** — Gallery swatches, rendered-markdown docs, feature progress,
   mark audit rows resolved, visual sign-off checklist in PR description.

### Parallel subagent execution

Use subagents for bounded workstreams with disjoint file ownership. The main
agent owns integration decisions, canonical docs, and any shared KMP mapper or
golden changes unless explicitly delegated as a single bounded task.

| Workstream | Owner | Scope | Outputs | Do not edit |
|------------|-------|-------|---------|-------------|
| PA0 web/reference audit | Explorer subagent | Extract web/Shiki reference colors or expected buckets for V1–V5 corpus | Notes for `path-a-visual-audit.md` gap table | Canonical docs, app code |
| PA0 mobile-state audit | Explorer subagent | Extract current mobile `CodeTokenRole` spans and role → token mapping | Notes for `path-a-visual-audit.md` gap table | Canonical docs, app code |
| PA1 Android wiring | Worker subagent | Android theme resolver, Compose rendered markdown role colors, Android gallery/tests | Android patch + changed file list | iOS files, KMP mapper, docs |
| PA1 iOS wiring | Worker subagent | Swift theme resolver, SwiftUI rendered markdown role colors, iOS gallery/tests | iOS patch + changed file list | Android files, KMP mapper, docs |
| PA2 mapper/golden work | Main agent or one KMP worker | Audit-proven scope mapper changes, optional golden regeneration, layer-7 shrink | Shared KMP patch + verification | Platform renderer files unless required |
| PA4 docs/sign-off | Main agent | `path-a-visual-audit.md`, `tokens.md`, rendered-markdown docs, feature progress, parity gaps, PR checklist | Final integrated docs | None |

Coordination rules:

- Start PA0 web/reference and PA0 mobile-state explorers in parallel.
- Start Android and iOS PA1 workers only after PA0 establishes the approved role
  → token mapping.
- Keep PA2 serialized with integration because `OpenCodeTextMate.kt`, generated
  goldens, and `OpenCodeSyntaxHighlighter.kt` are shared blast-radius files.
- Keep docs centralized in the main agent so progress, parity gaps, and audit
  decisions do not diverge across workers.
- Close worker agents after their patches are reviewed and integrated.

### Merge checklist

- [ ] `path-a-visual-audit.md` committed; ≥ 90% corpus color-bucket match
- [x] `Variable` → `syntax-variable`, `Operator` → `syntax-operator`,
      `Punctuation` → `syntax-punctuation` on Android and iOS
- [ ] `#baseUrl` declaration still `Plain`; `this.#baseUrl` still `Property`
- [ ] Top-10 `*-basic` golden gates green (Android + iOS)
- [ ] HttpClient / EventEmitter regression fixtures green
- [ ] `codeSpanColorsUseWebAlignedSyntaxTokens` (iOS) and Android span tests updated
- [ ] Color gallery shows new syntax tokens (light + dark)
- [ ] Component Gallery syntax sweep reviewed
- [ ] `tokens.md` + `rendered-markdown.md` updated
- [ ] `parity-gaps.md` + `progress.md` updated
- [ ] No new `CodeTokenRole` without distinct syntax token
- [ ] PR description includes web side-by-side note for V1–V3

### Suggested PR title

`feat(markdown): Path A syntax visual parity (role → token wiring + mapper)`

### Verification before merge

```bash
npm run markdown:highlighter:golden   # only if PA2 changed roles
cd android && ./gradlew :markdown-core:testDebugUnitTest :markdown-core:iosSimulatorArm64Test
cd android && ./gradlew :app:testDebugUnitTest --tests '*RenderedMarkdown*'
```

iOS: `OpenCodeRenderedMarkdownCodeSpanTests` via Xcode or macOS CI.

---

## Implementation order (within the PR)

```
PA0 (audit) ──► PA1 (wire tokens) ──► PA2 (mapper) ──► PA3? (roles) ──► PA4 (docs/gallery)
                                                      │
                                              layer-7 prune (if any)
```

PA5 (tokenizer gates) runs in a **separate** PR / track.

---

## File touch list (single PR)

| Area | Files |
|------|-------|
| Audit | `docs/features/native-rendered-markdown/path-a-visual-audit.md` (new) |
| KMP mapper | `OpenCodeTextMate.kt`, optional `generate-markdown-highlighter-fixtures.mjs`, golden resources |
| KMP roles | `OpenCodeMarkdown.kt`, iOS bridge types (PA3 only) |
| Layer-7 | `OpenCodeSyntaxHighlighter.kt` (shrink only) |
| Android theme | `ThemeResolver.kt`, `Color.kt` if needed |
| Android render | `OpenCodeRenderedMarkdown.kt`, `ColorGalleryScreen.kt` |
| iOS theme | `ThemeResolver.swift`, `OpenCodeTheme.swift` |
| iOS render | `OpenCodeRenderedMarkdown.swift` |
| Design system | `docs/design-system/tokens.md`, `rendered-markdown.md` |
| Feature docs | `parity-gaps.md`, `progress.md`, `web-vs-mobile-highlighter.md` |
| Tests | `OpenCodeRenderedMarkdownCodeSpanTests.swift`, Android rendered-markdown tests |

---

## Verification matrix

| Check | Command / location |
|-------|-------------------|
| Semantic goldens | `npm run markdown:highlighter:golden` |
| Android unit tests | `./gradlew :markdown-core:testDebugUnitTest` |
| Android app tests | `./gradlew :app:testDebugUnitTest --tests '*RenderedMarkdown*'` |
| iOS simulator tests | `./gradlew :markdown-core:iosSimulatorArm64Test` |
| iOS UI tests | `OpenCodeRenderedMarkdownCodeSpanTests` |
| Visual | Component Gallery syntax sweep, `path-a-visual-audit.md` sign-off |

---

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Variable color makes private `#fields` visible | Keep `highlightPrivateFields` → `Plain` |
| Preset vs OC-2 resolver mismatch | PA1.2 adds resolver defaults; gallery tests both |
| Golden drift on mapper change | Intentional regen + review in PR |
| iOS test encodes old mapping | Update `codeSpanColorsUseWebAlignedSyntaxTokens` in PA1 |
| Over-scoping PA3 | Hard gate: checklist + design-system token required |

---

## Open decisions (resolve in PA0)

1. **`Property` vs `syntax-object`** — Do object literal keys use `syntax-object`
   or stay in `syntax-constant` bucket?
2. **`Plain` vs `syntax-punctuation`** — Should `Plain` remain body color for
   truly unstyled text only?
3. **`Constant` vs `Property`** — Split colors or keep shared `syntax-constant`?
4. **Preset-only tokens** — Should built-in OC-2 resolver mirror opencode.json
   exactly or derive from palette seeds?

Default recommendation until audit says otherwise:

- PA1 wire variable / operator / punctuation only.
- Keep Property + Constant shared.
- Defer `syntax-object` until PA0 shows consistent web distinction.

---

## Related plans

- [shiki-visual-parity-paths-plan.md](./shiki-visual-parity-paths-plan.md) — Path B
  if PA0 residual gaps need scope-level fidelity
- [tokenizer-layer5-top10-sync-plan.md](./tokenizer-layer5-top10-sync-plan.md) —
  tokenizer oracle gates (layer 5)
