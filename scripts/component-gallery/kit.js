// Shared helpers and the component registry.
(() => {
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const I = (n, s) => `<span class="i"${s ? ` style="--s:${s}px"` : ""}>${window.DS.icons[n]?.svg ?? ""}</span>`;
  const sel = (key, options, def, label) => ({ key, label: label ?? key, type: "select", options, default: def ?? options[0] });
  const text = (key, def, label) => ({ key, label: label ?? key, type: "text", default: def });
  const bool = (key, def = false, label) => ({ key, label: label ?? key, type: "bool", default: def });
  const ACCENTS = { violet: ["#4c3d5c", "#e2d5f5", "#3a2f47", "#d8c8ee"], teal: ["#2d4a42", "#b8f5e0", "#243832", "#a8edd8"], amber: ["#5c3d28", "#ffb86c", "#4a3224", "#ffaa55"], blue: ["#2a3a55", "#a8d4ff", "#1e2d42", "#9ec8ff"], rose: ["#5c2d3d", "#ffb8d4", "#4a2533", "#ffa8cc"], green: ["#2d4a3d", "#b8f5d8", "#243832", "#a8edc8"], red: ["#5c2d2d", "#ffc8c8", "#4a2424", "#ffb8b8"], indigo: ["#2d3d5c", "#b8c8ff", "#24304a", "#a8b8ff"] };
  const TONES = { none: "", interactive: "--interactive", success: "--success", warning: "--warning", error: "--error", info: "--info" };
  const components = {};
  window.COMPONENTS = components;
  window.Kit = {
    esc, I, sel, text, bool, ACCENTS, TONES, components,
    /** Filled icon when the catalog has one, otherwise the outline icon. */
    IF: (n, s) => `<span class="i"${s ? ` style="--s:${s}px"` : ""}>${window.DS.icons[n]?.filled ?? window.DS.icons[n]?.svg ?? ""}</span>`, ctx: { dark: false, tokens: {}, platform: "ios" },
    /** Register components. Each needs id, params, render(props, ctx); optional states, sheet, tokens, doc. */
    define(list) { for (const c of list) { c.params ??= []; c.defaults = Object.fromEntries(c.params.map((x) => [x.key, x.default])); components[c.id] = c; } },
    /** Render another component with its defaults, for composition across groups. */
    use(id, props = {}, ctx = window.Kit.ctx) { const c = components[id]; if (!c) throw new Error("Unknown component: " + id); return c.render({ ...c.defaults, ...props }, ctx); },
  };
})();
