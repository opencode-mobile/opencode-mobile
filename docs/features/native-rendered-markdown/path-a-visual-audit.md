# Path A Visual Audit

Audit artifact for
[`shiki-visual-parity-path-a-plan.md`](./shiki-visual-parity-path-a-plan.md).
This records the current Path A role-to-token decisions and the remaining
audit rows that may require PA2 mapper changes or PA3 role expansion.

## Corpus

| ID | Source | Languages | Purpose |
|----|--------|-----------|---------|
| V1 | Top-10 `*-basic` Shiki semantic fixtures | JavaScript, TypeScript, Python, Kotlin, Swift, JSON, YAML, Shell, Rust, Go | Regression anchors for role coverage |
| V2 | `javascript-request-repro` | JavaScript | Private fields, async methods, templates, object keys |
| V3 | `javascript-http-error-repro` | JavaScript | Classes, constructor params, destructuring defaults, numbers, operators |
| V4 | Component Gallery syntax sweep | Multi-language | Native visual review on both platforms |
| V5 | `java-basic`, `csharp-basic`, popular-language smoke samples | Java, C# and popular pack | Common Session Chat languages outside the top-10 gate |

## Current Decisions

| Role or construct | Decision | Phase | Notes |
|-------------------|----------|-------|-------|
| `Variable` | Map to `syntax-variable` | PA1 done | Preset token existed but renderers used body text. |
| `Operator` | Map to `syntax-operator` | PA1 done | Preset token existed but renderers used body text. |
| `Punctuation` | Map to `syntax-punctuation` | PA1 done | Built-in resolver falls back to `text-strong`; presets may override. |
| `Plain` | Keep body text | PA1 done | Used for fallback text and intentionally plain private field declarations. |
| `Property`, `Constant` | Keep `syntax-constant` | PA1 done | Android and iOS now align with the canonical token table. |
| `Number`, `Attribute` | Keep `syntax-property` | PA1 done | No Path A change. |
| `syntax-object` | Defer | PA2/PA3 | Presets define it, but no role should use it until the audit proves a repeated distinction. |

## Gap Table

| Snippet | Text range | Web token | Mobile role | Mobile token | Gap type | Phase | Status |
|---------|------------|-----------|-------------|--------------|----------|-------|--------|
| `javascript-basic` | `answer` declaration/use | `syntax-variable` | `Variable` | `syntax-variable` | Color only | PA1 | Resolved |
| `javascript-basic` | `=` | `syntax-operator` | `Operator` | `syntax-operator` | Color only | PA1 | Resolved |
| `javascript-basic` | `({`, `,`, `:`, `})` | `syntax-punctuation` | `Punctuation` | `syntax-punctuation` | Color only | PA1 | Resolved |
| `typescript-basic` | `session` | `syntax-variable` | `Variable` | `syntax-variable` | Color only | PA1 | Resolved |
| `javascript-http-error-repro` | `maxRetries`, `baseDelay`, `maxDelay`, `retryOn` | `syntax-variable` | `Variable` | `syntax-variable` | Color only | PA1 | Resolved |
| `javascript-request-repro` | `this.#baseUrl`, `this.#headers` private members | `syntax-constant` | `Property` | `syntax-constant` | Verify only | PA2 | Open |
| `javascript-request-repro` | `#baseUrl` declaration | Body/plain | none | Body/plain | Intentional exception | Keep | Resolved |
| `javascript-request-repro` | `url`, `headers`, `body`, `params`, `signal` object keys | TBD: `syntax-constant`, `syntax-property`, or `syntax-object` | `Property` | `syntax-constant` | Role/color verify | PA2 | Open |
| `typescript-basic` | `id`, `active` in type literal | TBD: `syntax-variable` vs property/object bucket | `Variable` | `syntax-variable` | Role? | PA2/PA3 | Open |
| `javascript-request-repro` | `Object`, `Map`, namespace/module receivers | TBD: `syntax-object` or existing bucket | mixed | mixed | Role? | PA3 gate | Open |
| `javascript-request-repro` | `async` method shorthand | likely `syntax-keyword` | `Variable` in semantic golden | `syntax-variable` | Role | PA2 | Open |
| `javascript-request-repro` | `fromEntries`, call identifiers | `syntax-primitive` if web treats as function | `Function` | `syntax-primitive` | Verify only | PA2 | Open |
| `javascript-http-error-repro` | `3`, `1000`, `10000`, status codes | `syntax-property` | `Number` | `syntax-property` | None | n/a | Resolved |
| `java-basic` | `System.out.println` receiver/member/call | TBD per web | mixed property/function | mixed | Role? | PA2 | Open |
| `csharp-basic` | `Label`, params, interpolation vars | variable/function buckets | current golden roles | mixed | Smoke verify | PA2 | Open |

## PA2/PA3 Decision Notes

- Do not assign `syntax-object` in PA1. It is available in presets and resolver
  fallbacks, but the render tree has no distinct `Object` role.
- Do not add `Builtin`, `Parameter`, or `Namespace` roles unless at least three
  recurring audit rows across at least two languages prove the distinction needs
  a different syntax token.
- Keep `highlightPrivateFields`: declarations such as `#baseUrl;` stay plain,
  while member access such as `this.#baseUrl` remains `Property`.
- Prefer mapper fixes over new layer-7 post-processing. Remove existing layer-7
  fixups only after golden and repro fixtures prove coverage.

## Visual Sign-Off

- [ ] OpenCode preset, light and dark.
- [ ] One alternate preset, such as One Dark.
- [ ] V1 top-10 gallery sweep.
- [ ] V2/V3 JavaScript repro review, including private fields.
- [ ] V5 Java/C# smoke review.
