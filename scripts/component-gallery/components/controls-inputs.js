// Component group: controls-inputs. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool } = Kit;

  /* Shared pieces */
  const spinner = (ctx, size, stroke = 2) => {
    if (ctx.platform === "ios") {
      const spokes = Array.from({ length: 8 }, (_, i) => `<line x1="12" y1="3" x2="12" y2="7.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity="${(0.25 + (i / 7) * 0.75).toFixed(2)}" transform="rotate(${i * 45} 12 12)"/>`).join("");
      return `<svg class="c-spin-ios" viewBox="0 0 24 24" style="width:${size}px;height:${size}px" aria-hidden="true">${spokes}</svg>`;
    }
    const r = (size - stroke) / 2, c = size / 2;
    return `<svg class="c-spin-ring" viewBox="0 0 ${size} ${size}" style="width:${size}px;height:${size}px" aria-hidden="true"><circle class="track" cx="${c}" cy="${c}" r="${r}" stroke-width="${stroke}"/><circle class="arc" cx="${c}" cy="${c}" r="${r}" stroke-width="${stroke}" pathLength="100"/></svg>`;
  };
  const once = (el, key) => { if (el.dataset[key]) return false; el.dataset[key] = "1"; return true; };
  const graphemes = (s) => (window.Intl?.Segmenter ? [...new Intl.Segmenter().segment(s)].length : [...s].length);
  const lengthOf = (s, platform) => (platform === "ios" ? graphemes(s) : s.length);
  const lengthLabel = (n, max) => (max && n * 10 >= max * 9 ? `${n}/${max}` : "");

  /* Text Input */
  const HINTS = { text: "Project name", url: "Server URL", username: "Username", password: "Password", filter: "Filter models", search: "Search in All projects" };
  const VALUES = { text: "OpenCode Mobile", url: "http://127.0.0.1:4096/", username: "opencode", password: "correct horse battery staple", filter: "claude" };
  const searchValue = (ctx) => (ctx.platform === "ios" ? "search" : "invoice");
  const clearButton = (ctx, disabled) => `<button class="c-ti-clear" type="button" aria-label="Clear search"${disabled ? " disabled" : ""}><span>${I("close", ctx.platform === "ios" ? 12 : 18)}</span></button>`;
  const searchEnd = (p, ctx, value) => {
    if (p.busy) return `<span class="c-ti-busy" role="progressbar" aria-label="Searching" style="--spin:var(${ctx.platform === "ios" ? "--icon-base" : "--text-weak"})">${spinner(ctx, ctx.platform === "ios" ? 20 : 18)}</span>`;
    if (!value) return "";
    if (ctx.platform === "android" && p.disabled) return "";
    return clearButton(ctx, p.disabled);
  };
  const textInput = (p, ctx) => {
    const multi = !!p.multiline && p.kind === "text";
    const value = p.value === "@sample" ? (p.kind === "search" ? searchValue(ctx) : VALUES[p.kind]) : p.value ?? "";
    const hint = p.hint || HINTS[p.kind];
    const max = multi && p.maxLength !== "none" ? Number(p.maxLength) : 0;
    const auto = p.kind === "text" ? 'autocapitalize="sentences" autocorrect="on" spellcheck="true"' : 'autocapitalize="none" autocorrect="off" spellcheck="false"';
    const enter = p.kind === "search" && ctx.platform === "ios" ? "search" : "done";
    const type = p.kind === "password" ? "password" : "text";
    const mode = p.kind === "url" ? ' inputmode="url"' : "";
    const lh = ctx.platform === "ios" ? 19.09 : 26;
    const field = multi
      ? `<textarea class="c-ti-input" rows="4" data-min="4" data-max="8" data-lh="${lh}" aria-label="${esc(hint)}" ${auto}${max ? ` data-limit="${max}"` : ""}${p.disabled ? " disabled" : ""}>${esc(value)}</textarea>`
      : `<input class="c-ti-input" type="${type}"${mode} enterkeyhint="${enter}" aria-label="${esc(hint)}" value="${esc(value)}" ${auto}${p.disabled ? " disabled" : ""}>`;
    const hintText = `<span class="c-ti-hint" aria-hidden="true"${value ? " hidden" : ""}>${esc(hint)}</span>`;
    const count = lengthLabel(lengthOf(value, ctx.platform), max);
    const cls = ["c-ti", p.focused && "focus", p.errorMessage && "err", p.disabled && "off"].filter(Boolean).join(" ");
    return `<div class="${cls}" data-kind="${p.kind}"${p.busy ? ' data-busy="1"' : ""}><div class="c-ti-box${multi ? " multi" : ""}${p.kind === "search" ? " search" : ""}">${p.kind === "search" ? `<span class="c-ti-lead">${I("search", 18)}</span>` : ""}<span class="c-ti-field">${hintText}${field}</span>${p.kind === "search" ? `<span class="c-ti-end">${searchEnd(p, ctx, value)}</span>` : ""}</div>${p.errorMessage ? `<div class="c-ti-msg">${esc(p.errorMessage)}</div>` : ""}${max ? `<div class="c-ti-count"${count ? "" : " hidden"}>${count}</div>` : ""}</div>`;
  };
  const growArea = (a) => {
    const ts = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--ts")) || 1;
    const line = Number(a.dataset.lh) * ts;
    a.style.height = "auto";
    a.style.height = Math.min(Math.max(a.scrollHeight, line * Number(a.dataset.min)), line * Number(a.dataset.max)) + "px";
  };
  const mountTextInput = (stage, ctx) => {
    stage.querySelectorAll(".c-ti").forEach((root) => {
      if (!once(root, "bound")) return;
      const input = root.querySelector(".c-ti-input");
      if (!input) return;
      const end = root.querySelector(".c-ti-end");
      const counter = root.querySelector(".c-ti-count");
      const limit = Number(input.dataset.limit || 0);
      const hintEl = root.querySelector(".c-ti-hint");
      const syncEnd = () => {
        hintEl.hidden = !!input.value;
        if (!end || root.dataset.busy) return;
        const has = !!input.value;
        const current = end.querySelector(".c-ti-clear");
        if (has && !current && !(ctx.platform === "android" && input.disabled)) end.innerHTML = clearButton(ctx, input.disabled);
        else if (!has && current) end.innerHTML = "";
      };
      const syncCount = () => {
        if (!counter) return;
        const label = lengthLabel(lengthOf(input.value, ctx.platform), limit);
        counter.textContent = label;
        counter.hidden = !label;
      };
      input.addEventListener("input", () => {
        if (limit && lengthOf(input.value, ctx.platform) > limit) {
          input.value = ctx.platform === "ios" ? [...new Intl.Segmenter().segment(input.value)].slice(0, limit).map((x) => x.segment).join("") : input.value.slice(0, limit);
        }
        if (input.tagName === "TEXTAREA") growArea(input);
        syncEnd(); syncCount();
      });
      end?.addEventListener("click", (e) => {
        if (!e.target.closest(".c-ti-clear") || input.disabled) return;
        input.value = ""; syncEnd(); input.focus();
      });
      if (input.tagName === "TEXTAREA") growArea(input);
    });
  };

  /* Server Address Input */
  const parseAddress = (v) => {
    const m = String(v ?? "").trim().match(/^(https?):\/\/(.*)$/i);
    const scheme = m ? m[1].toLowerCase() : "http";
    const host = (m ? m[2] : String(v ?? "")).replace(/[/?#].*$/, "");
    return { scheme, host };
  };
  const applyHostEdit = (value, scheme) => {
    const m = value.trim().match(/^(https?):\/\/(.*)$/i);
    const next = m ? m[1].toLowerCase() : scheme;
    const raw = m ? m[2].replace(/[/?#].*$/, "") : value;
    return { scheme: next, host: raw.replace(/[^A-Za-z0-9.\-_:[\]]/g, "") };
  };
  const serverAddress = (p) => {
    const { scheme, host } = parseAddress(p.value);
    const cls = ["c-sa", p.focused && "focus", p.errorMessage && "err", p.disabled && "off"].filter(Boolean).join(" ");
    return `<div class="${cls}"><div class="c-sa-box"><button class="c-sa-scheme press" type="button" aria-label="Scheme ${scheme}" aria-haspopup="dialog"${p.disabled ? " disabled" : ""}><b>${scheme}</b>${I("chevron_right", 16)}</button><input class="c-sa-host" inputmode="url" autocapitalize="none" autocorrect="off" spellcheck="false" enterkeyhint="done" placeholder="${esc(p.hint)}" aria-label="${esc(p.hint)}" value="${esc(host)}"${p.disabled ? " disabled" : ""}></div>${p.errorMessage ? `<div class="c-sa-msg">${esc(p.errorMessage)}</div>` : ""}</div>`;
  };
  const mountServerAddress = (stage) => {
    stage.querySelectorAll(".c-sa").forEach((root) => {
      if (!once(root, "bound")) return;
      const btn = root.querySelector(".c-sa-scheme"), label = btn.querySelector("b"), host = root.querySelector(".c-sa-host");
      const setScheme = (s) => { label.textContent = s; btn.setAttribute("aria-label", `Scheme ${s}`); };
      host.addEventListener("input", () => {
        const next = applyHostEdit(host.value, label.textContent);
        if (next.host !== host.value) host.value = next.host;
        setScheme(next.scheme);
      });
      btn.addEventListener("click", () => {
        if (btn.disabled) return;
        const scrim = document.createElement("div");
        scrim.className = "c-sa-scrim";
        scrim.innerHTML = `<div class="c-sa-sheet" role="dialog" aria-label="Scheme"><h6>Scheme</h6><button class="press" type="button">http</button><button class="press" type="button">https</button></div>`;
        scrim.addEventListener("click", (e) => {
          const option = e.target.closest("button");
          if (option) setScheme(option.textContent);
          if (option || e.target === scrim) scrim.remove();
        });
        document.body.append(scrim);
      });
    });
  };

  /* Sliders */
  const STEPS = ["Smallest", "Extra small", "Small", "Default", "Large", "Extra large"];
  const FACTORS = [0.78, 0.85, 0.92, 1, 1.12, 1.26];
  const GALLERY_STEPS = ["Extra small", "Small", "Default", "Large", "Extra large"];
  const stepSlider = (p, ctx) => {
    const index = Math.max(0, STEPS.indexOf(p.step));
    const last = STEPS.length - 1, def = p.markDefault ? 3 : -1;
    const ticks = STEPS.map((_, i) => `<i class="c-step-tick${i === def ? " def" : ""}${i <= index ? " on" : ""}" data-i="${i}" style="--x:${i / last}"></i>`).join("");
    return `<div class="c-step-wrap" style="--step-f:${FACTORS[index]}"><div class="c-step-head"><b>Size</b><span class="c-step-val">${esc(STEPS[index])}</span></div><div class="c-step${ctx.dark ? " dark" : ""}"><span class="c-step-a" style="font-size:12px">A</span><div class="c-step-track" role="slider" tabindex="0" aria-label="Size" aria-valuemin="0" aria-valuemax="${last}" aria-valuenow="${index}" aria-valuetext="${esc(STEPS[index])}" data-count="${STEPS.length}" data-inset="12" style="--f:${index / last}"><span class="c-step-line"></span><span class="c-step-fill"></span>${ticks}<span class="c-step-knob"></span></div><span class="c-step-a" style="font-size:19px">A</span></div><div class="c-step-sample">The quick brown fox</div></div>`;
  };
  const textSizeSlider = (p, ctx) => {
    const names = p.steps === "five" ? GALLERY_STEPS : STEPS;
    const found = names.indexOf(p.step);
    const index = found >= 0 ? found : Math.floor(names.length / 2);
    const last = names.length - 1, def = Math.floor(names.length / 2);
    const ticks = names.map((_, i) => `<i class="c-tss-tick${i === def ? " def" : ""}" style="--x:${i / last}"></i>`).join("");
    return `<div class="c-tss${ctx.dark ? " dark" : ""}${p.disabled ? " off" : ""}" data-names="${esc(names.join("|"))}"><div class="c-tss-head"><b>${esc(p.title)}</b><span class="c-tss-val">${esc(names[index])}</span></div><div class="c-tss-row"><span class="c-tss-sm">A</span><div class="c-tss-track" role="slider" tabindex="${p.disabled ? -1 : 0}" aria-label="${esc(p.title)}" aria-valuemin="0" aria-valuemax="${last}" aria-valuenow="${index}" aria-valuetext="${esc(names[index])}"${p.disabled ? ' aria-disabled="true"' : ""} data-count="${names.length}" data-inset="11" style="--f:${index / last}"><span class="c-tss-line"></span><span class="c-tss-fill"></span>${ticks}<span class="c-tss-thumb"></span></div><span class="c-tss-lg">A</span></div></div>`;
  };
  const bindTrack = (track, onChange) => {
    const count = Number(track.dataset.count), inset = Number(track.dataset.inset);
    const set = (i) => {
      const next = Math.min(Math.max(i, 0), count - 1);
      if (next === Number(track.getAttribute("aria-valuenow"))) return;
      track.setAttribute("aria-valuenow", next);
      track.style.setProperty("--f", next / (count - 1));
      onChange(next);
    };
    const fromEvent = (e) => {
      const r = track.getBoundingClientRect(), k = r.width / track.offsetWidth || 1;
      const usable = Math.max(1, track.offsetWidth - inset * 2);
      set(Math.round((((e.clientX - r.left) / k - inset) / usable) * (count - 1)));
    };
    track.addEventListener("pointerdown", (e) => { track.setPointerCapture(e.pointerId); fromEvent(e); });
    track.addEventListener("pointermove", (e) => { if (track.hasPointerCapture(e.pointerId)) fromEvent(e); });
    track.addEventListener("keydown", (e) => {
      const d = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[e.key];
      if (!d) return;
      e.preventDefault();
      set(Number(track.getAttribute("aria-valuenow")) + d);
    });
  };
  const mountStepSlider = (stage) => {
    stage.querySelectorAll(".c-step-wrap").forEach((root) => {
      if (!once(root, "bound")) return;
      const track = root.querySelector(".c-step-track");
      bindTrack(track, (i) => {
        root.querySelector(".c-step-val").textContent = STEPS[i];
        root.style.setProperty("--step-f", FACTORS[i]);
        track.setAttribute("aria-valuetext", STEPS[i]);
        track.querySelectorAll(".c-step-tick").forEach((t) => t.classList.toggle("on", Number(t.dataset.i) <= i));
      });
    });
  };
  const mountTextSizeSlider = (stage) => {
    stage.querySelectorAll(".c-tss:not(.off)").forEach((root) => {
      if (!once(root, "bound")) return;
      const names = root.dataset.names.split("|"), track = root.querySelector(".c-tss-track");
      bindTrack(track, (i) => {
        root.querySelector(".c-tss-val").textContent = names[i];
        track.setAttribute("aria-valuetext", names[i]);
      });
    });
  };

  /* Composer Suggestion Overlay */
  const fileIcon = (path, directory) => {
    const m = window.DS.fileIconMaps, base = path.split("/").pop(), lower = base.toLowerCase();
    let name;
    if (directory) name = m.folderNames[lower] ?? m.defaults.folder;
    else {
      name = m.fileNames[base] ?? m.fileNames[lower];
      const parts = lower.split(".");
      for (let i = 1; !name && i < parts.length; i++) name = m.fileExtensions[parts.slice(i).join(".")];
      name ??= m.defaults.file;
    }
    return `<img src="assets/file-types/${esc(m.icons[name] ?? m.icons[m.defaults.file])}" alt="">`;
  };
  const SUGGESTIONS = {
    populated: () => Array.from({ length: 8 }, (_, i) => ({ title: `/command-${i + 1}`, subtitle: `Description ${i + 1}`, badge: (i + 1) % 3 === 0 ? "skill" : (i + 1) % 2 === 0 ? "mcp" : "" })),
    short: () => [{ title: "/init", subtitle: "Create AGENTS.md" }, { title: "/my-skill", subtitle: "A skill", badge: "skill" }],
    mentions: () => [{ title: "@build", subtitle: "Default agent", glyph: "agent" }, ...["Package.swift", "app/build.gradle", "src/main.kt", "web/App.tsx", "README.md", "src"].map((path) => ({ title: path, subtitle: "File", file: path, directory: !path.includes(".") }))],
    empty: () => [],
  };
  const suggestionRow = (x) => {
    const mark = x.glyph ? I(x.glyph, 16) : x.file ? fileIcon(x.file, x.directory) : "";
    const label = [x.badge && `[${x.badge}]`, x.title, x.subtitle].filter(Boolean).join(", ");
    return `<button class="c-sg-row press" type="button" aria-label="${esc(label)}">${mark ? `<span class="c-sg-mark">${mark}</span>` : ""}<span class="c-sg-text"><span class="c-sg-line">${x.badge ? `<span class="c-sg-badge">[${esc(x.badge)}]</span>` : ""}<span class="c-sg-title">${esc(x.title)}</span></span>${x.subtitle ? `<span class="c-sg-sub">${esc(x.subtitle)}</span>` : ""}</span></button>`;
  };
  const suggestions = (p) => {
    const items = (SUGGESTIONS[p.scenario] ?? SUGGESTIONS.populated)();
    return `<div class="c-sg float">${items.length ? `<div class="c-sg-list">${items.map(suggestionRow).join("")}</div>` : `<div class="c-sg-empty">${esc(p.emptyMessage || "No commands")}</div>`}</div>`;
  };

  /* Code Snippet */
  const SYNTAX_SAMPLE = `<span class="c"># Start the server</span>\n<span class="k">export</span> PORT=<span class="n">4096</span>\n<span class="f">opencode</span> serve <span class="s">"--port=$PORT"</span>`;
  const codeSnippet = (p) => `<div class="c-code ${p.surface === "syntaxPreset" ? "syntax" : p.surface}">${p.surface === "syntaxPreset" ? SYNTAX_SAMPLE : esc(p.code)}</div>`;

  /* Feedback */
  const emptyState = (p) => {
    const actions = p.primary || p.secondary
      ? `<div class="c-empty-actions">${p.primary ? Kit.use("button", { variant: "fullWidthText", contrast: "high", title: p.primary }) : ""}${p.secondary ? Kit.use("button", { variant: "fullWidthText", contrast: "high", title: p.secondary }) : ""}</div>`
      : "";
    return `<div class="c-empty${p.fills ? "" : " hug"}">${p.icon ? `<span class="c-empty-icon">${I("create_new_folder", 40)}</span>` : ""}<div class="c-empty-title">${esc(p.title)}</div><div class="c-empty-msg">${esc(p.message)}</div>${actions}</div>`;
  };
  const statusStack = (p, ctx) => {
    const labeled = p.style === "labeled";
    const name = labeled ? `${p.title}. ${p.subtitle}` : p.title;
    const ring = ctx.platform === "ios" ? spinner(ctx, 32) : spinner(ctx, 32, 2.5);
    return `<div class="c-ss${p.reveal === "afterGrace" ? " grace" : ""}" role="status" aria-label="${esc(name)}">${ring}${labeled ? `<div class="c-ss-title" aria-hidden="true">${esc(p.title)}</div><div class="c-ss-sub" aria-hidden="true">${esc(p.subtitle)}</div>` : ""}</div>`;
  };
  const inlineNotice = (p) => `<div class="c-note ${p.variant} ${p.tone}"><div class="c-note-msg">${esc(p.message)}</div>${p.action ? Kit.use("button", { variant: "compactText", contrast: "low", title: p.actionTitle }) : ""}</div>`;
  const keyboardHint = (p, ctx) => `<div class="c-kbd ${p.tone}"${p.tone === "warning" && ctx.platform === "android" ? ' aria-live="polite"' : ""}>${esc(p.text)}</div>`;

  const inputParams = [sel("kind", ["text", "url", "username", "password", "filter", "search"], "url"), text("hint", ""), text("value", ""), text("errorMessage", ""), bool("focused"), bool("disabled"), bool("busy"), bool("multiline"), sel("maxLength", ["none", "40"], "none")];
  const kinds = ["text", "url", "username", "password", "filter", "search"];
  const LONG = "Summarize the release notes for this week";

  Kit.define([
    { id: "text-input", doc: "text-input", align: "stretch", pad: true, params: inputParams,
      states: [["Empty", {}], ["Populated", { value: "@sample" }], ["Focused", { focused: true }], ["Error", { value: "@sample", errorMessage: "Enter a valid server url" }], ["Disabled", { value: "@sample", disabled: true }], ["Search, typed", { kind: "search", value: "@sample" }], ["Search, busy", { kind: "search", value: "@sample", busy: true }], ["Multi-line", { kind: "text", multiline: true, value: "@sample" }], ["Near limit", { kind: "text", multiline: true, maxLength: "40", value: LONG.slice(0, 37) }]],
      sheet: [
        { title: "Kinds, empty", kind: "stack", inset: true, items: kinds.map((k) => [k, { kind: k }]) },
        { title: "Kinds, populated", kind: "stack", inset: true, items: kinds.map((k) => [k, { kind: k, value: "@sample" }]) },
        { title: "States", kind: "stack", inset: true, base: { kind: "url", value: "@sample" }, items: [["Focused", { focused: true }], ["Error", { errorMessage: "Enter a valid server url" }], ["Focused with error", { focused: true, errorMessage: "Enter a valid server url" }], ["Disabled", { disabled: true }], ["Disabled with error", { disabled: true, errorMessage: "Enter a valid server url" }]] },
        { title: "Search", kind: "stack", inset: true, base: { kind: "search" }, items: [["Empty", {}], ["Typed, clear button", { value: "@sample" }], ["Busy", { value: "@sample", busy: true }], ["Busy, empty", { busy: true }], ["Focused", { value: "@sample", focused: true }], ["Disabled", { value: "@sample", disabled: true }]] },
        { title: "Multi-line (4 to 8 lines)", kind: "stack", inset: true, base: { kind: "text", multiline: true }, items: [["Empty", {}], ["Populated", { value: "@sample" }], ["Error", { value: "@sample", errorMessage: "Enter a valid project name" }], ["Under 90% of limit, no count", { maxLength: "40", value: "Summarize the notes" }], ["At 90% of limit", { maxLength: "40", value: LONG.slice(0, 36) }], ["At limit", { maxLength: "40", value: LONG.slice(0, 40) }]] },
      ],
      tokens: [["textInput.cornerRadius", 8], ["textInput.minHeight", 48], ["textInput.paddingHorizontal", 12], ["multiline.paddingVertical", 12], ["border.idle", "1 border-weaker-base"], ["border.focus", "2 interactive"], ["border.error", "2 error"], ["disabled.opacity", 0.6], ["search.icon", 18], ["search.gap", 8], ["search.clear.target (iOS)", 24], ["search.clear.target (Android)", 32], ["message.gap", 6], ["type", "type.body, type.caption"]],
      source: { ios: "Components/Input/OpenCodeTextInput.swift", android: "ui/components/input/OpenCodeTextInput.kt" },
      divergences: [
        { severity: "high", title: "Search keyboard action is Search on iOS and Done on Android" },
        { severity: "medium", title: "Search icon, clear button, target, and spinner differ" },
        { severity: "medium", title: "Length limit counts characters on iOS and UTF-16 units on Android" },
        { severity: "low", title: "Disabled dims the error text on Android only" },
        { severity: "low", title: "iOS single-line placeholder uses the system color, not text-weaker" },
        { severity: "low", title: "Doc omits filter, search, go, multi-line, and length limit" },
      ],
      render: textInput, mount: mountTextInput },

    { id: "server-address-input", doc: "server-address-input", align: "stretch", pad: true,
      params: [text("value", "http://127.0.0.1:4096/"), text("hint", "Host"), text("errorMessage", ""), bool("focused"), bool("disabled")],
      states: [["HTTP", {}], ["HTTPS hostname", { value: "https://example.com:8080/" }], ["Empty", { value: "http://" }], ["Focused", { focused: true }], ["Error", { errorMessage: "Enter a valid host and port" }], ["Disabled", { disabled: true }]],
      sheet: [
        { title: "Values", kind: "stack", inset: true, items: [["HTTP with IP and port", {}], ["HTTPS hostname", { value: "https://example.com:8080/" }], ["Empty host", { value: "http://" }]] },
        { title: "States", kind: "stack", inset: true, items: [["Focused", { focused: true }], ["Error", { errorMessage: "Enter a valid host and port" }], ["Focused with error", { focused: true, errorMessage: "Enter a valid host and port" }], ["Disabled", { disabled: true }], ["Disabled, empty", { disabled: true, value: "http://" }]] },
      ],
      tokens: [["textInput.cornerRadius", 8], ["textInput.minHeight", 48], ["scheme.paddingStart", 16], ["scheme.paddingEnd", 8], ["scheme.iconGap", 4], ["scheme.icon", 16], ["host.paddingStart", 8], ["host.paddingEnd", 16], ["border.focus (iOS)", "2 interactive"], ["border.focus (Android)", "2 text-interactive-base"], ["error.gap (iOS)", 6], ["error.gap (Android)", 4], ["error.inset (Android)", 16]],
      source: { ios: "Components/Input/OpenCodeServerAddressInput.swift", android: "ui/components/input/OpenCodeServerAddressInput.kt" },
      divergences: [
        { severity: "high", title: "Return runs onSubmit on iOS; Android has no onSubmit" },
        { severity: "medium", title: "Disabled dims the field on iOS and only the scheme label on Android" },
        { severity: "medium", title: "Focus color, hint color, and error placement differ" },
        { severity: "low", title: "Doc describes a vertical divider that neither platform draws" },
      ],
      render: serverAddress, mount: mountServerAddress },

    { id: "text-size-slider", align: "stretch", pad: true,
      params: [text("title", "Size"), sel("step", STEPS, "Default"), sel("steps", ["six", "five"], "six"), bool("disabled")],
      states: STEPS.map((s) => [s, { step: s }]).concat([["Disabled", { step: "Extra large", disabled: true }]]),
      sheet: [
        { title: "Steps (production, six)", kind: "stack", inset: true, items: STEPS.map((s) => [s, { step: s }]) },
        { title: "Gallery (five steps)", kind: "stack", inset: true, base: { steps: "five" }, items: [["Interactive", { step: "Default" }], ["Disabled", { step: "Extra large", disabled: true }]] },
      ],
      tokens: [["card.radius", 12], ["card.padding", "12 16 4"], ["row.spacing", 12], ["track.height", 2], ["tick.size", 8], ["thumb.size", 22], ["track", "border-weak-base"], ["track.active", "text-strong"], ["tick.default", "text-weaker"], ["thumb.light", "surface-raised-base"], ["thumb.dark", "text-strong"]],
      spec: "## Intent\nA card that picks one named text size for a region in Settings › Appearance.\n\n## Public API\n- `title`, `stepNames` (two or more), `selectedIndex`, `onSelectedIndexChange`\n- `defaultIndex` (defaults to the middle step), `accessibilityLabel`, `enabled`\n\n## Behavior\n- Drag or tap snaps to a step.\n- The default step's tick uses `text-weaker`; the others use `border-weak-base`.\n- Screen readers hear the label and the step name.\n- iOS has the same concept as Step Slider.",
      source: { android: "ui/components/input/OpenCodeTextSizeSlider.kt" },
      divergences: [
        { severity: "medium", title: "Same concept as iOS Step Slider with different structure, geometry, and colors" },
        { severity: "medium", title: "Disabled looks identical to enabled" },
        { severity: "low", title: "Gallery shows five steps; production has six" },
      ],
      render: textSizeSlider, mount: mountTextSizeSlider },

    { id: "step-slider", align: "stretch", pad: true,
      params: [sel("step", STEPS, "Default"), bool("markDefault", true, "default tick")],
      states: STEPS.map((s) => [s, { step: s }]).concat([["No default mark", { markDefault: false }]]),
      sheet: [
        { title: "Steps", kind: "stack", inset: true, items: STEPS.map((s) => [s, { step: s }]) },
        { title: "Default mark", kind: "stack", inset: true, items: [["defaultIndex = 3", {}], ["defaultIndex = nil", { markDefault: false }]] },
      ],
      tokens: [["track.height", 4], ["tick.size", 6], ["tick.default", 10], ["knob.size", 24], ["glyph.small", 12], ["glyph.large", 19], ["row.spacing", 12], ["minHeight", 44], ["track", "border-weaker-base"], ["track.active", "text-strong"], ["tick.inactive", "border-weak-base"], ["knob.light", "background-base"], ["knob.dark", "text-strong"], ["motion", "easeOut 120ms"]],
      spec: "## Intent\nA slider that snaps to a few named steps. Settings › Appearance uses it for text size.\n\n## Public API\n- `stepCount`, `selectedIndex`, `defaultIndex` (optional), `variant` (`textSize`)\n- `accessibilityLabel`, `accessibilityValue`, `onSelect`\n\n## Behavior\n- Drag or tap selects the nearest step, moving in 120ms ease-out.\n- Ticks at or before the selection use `text-strong`.\n- VoiceOver adjusts by one step and reads the step name.\n- The title row and sample belong to the host screen and are shown here as in the gallery.\n- Android has the same concept as Text Size Slider.",
      source: { ios: "Components/Input/OpenCodeStepSlider.swift" },
      divergences: [{ severity: "medium", title: "Same concept as Android Text Size Slider with different structure, geometry, and colors" }],
      render: stepSlider, mount: mountStepSlider },

    { id: "composer-suggestion-overlay", doc: "composer-suggestion-overlay", align: "stretch", pad: true,
      params: [sel("scenario", ["populated", "short", "mentions", "empty"]), text("emptyMessage", "No commands")],
      states: [["Populated (scrolls)", { scenario: "populated" }], ["Two rows", { scenario: "short" }], ["Mentions", { scenario: "mentions" }], ["Empty", { scenario: "empty" }]],
      sheet: [{ title: "States", kind: "stack", inset: true, items: [["Populated, scrolls past four rows", { scenario: "populated" }], ["Hugs two rows", { scenario: "short" }], ["Mentions with file icons", { scenario: "mentions" }], ["Empty", { scenario: "empty" }]] }],
      tokens: [["composerSuggestionOverlay.cornerRadius", 12], ["composerSuggestionOverlay.visibleItemLimit", 4], ["composerSuggestionOverlay.rowPaddingVertical", 10], ["composerSuggestionOverlay.rowInternalSpacing", 2], ["row.paddingHorizontal", 16], ["icon.size", 16], ["icon.gap", 8], ["badge.gap", 6], ["maxHeight (iOS)", 248], ["maxHeight (Android)", 264], ["border", "1 border-weaker-base"]],
      source: { ios: "Components/Input/OpenCodeComposerSuggestionOverlay.swift", android: "ui/components/input/OpenCodeComposerSuggestionOverlay.kt" },
      divergences: [
        { severity: "medium", title: "List height budget differs, and Android's is short of four subtitle rows" },
        { severity: "low", title: "Fallback empty message is hardcoded English on both platforms" },
        { severity: "low", title: "Doc omits the leading icon" },
      ],
      render: suggestions },

    { id: "code-snippet", align: "stretch", pad: true,
      params: [text("code", "opencode serve"), sel("surface", ["raised", "base", "syntaxPreset"])],
      states: [["Raised", {}], ["Base", { code: "opencode2 pair", surface: "base" }], ["Syntax colored", { surface: "syntaxPreset" }], ["Long, wraps", { code: "OPENCODE_SERVER_PASSWORD=secret opencode serve" }]],
      sheet: [{ title: "Surfaces", kind: "stack", inset: true, items: [["Raised", {}], ["Base", { code: "opencode2 pair", surface: "base" }], ["Syntax preset", { surface: "syntaxPreset" }], ["Long command wraps", { code: "OPENCODE_SERVER_PASSWORD=secret opencode serve" }]] }],
      tokens: [["paddingHorizontal", 12], ["paddingVertical", 10], ["cornerRadius", 10], ["fill.raised", "surface-raised-base"], ["fill.base", "surface-base"], ["text", "text-strong"], ["type", "type.code"]],
      spec: "## Intent\nA monospace block for a command or code.\n\n## Public API\n- Plain or syntax-colored text\n- `surface`: `raised` on the page background, `base` on a raised card, or a syntax preset's editor background\n\n## Behavior\n- Fills the width and wraps long lines.\n- Syntax colors stay on their spans; other text uses `text-strong` or the preset's plain color.\n- Plain text defaults to `raised`; colored text defaults to `base`.",
      source: { ios: "Components/Feedback/OpenCodeCodeSnippet.swift", android: "ui/components/feedback/OpenCodeCodeSnippet.kt" },
      divergences: [{ severity: "low", title: "Android rejects blank code; iOS rejects only empty code" }],
      render: codeSnippet },

    { id: "empty-state", doc: "empty-state", align: "stretch", cellHeight: 270,
      params: [text("title", "No projects yet"), text("message", "Tap the Add project icon below to choose a folder on your server."), bool("icon", true), text("primary", ""), text("secondary", ""), bool("fills", true, "fills space")],
      states: [["With icon", {}], ["No icon", { icon: false }], ["Two actions", { icon: false, title: "Unable to connect", message: "The server did not answer.", primary: "Retry", secondary: "Edit connection" }], ["One action", { icon: false, title: "Unable to connect", message: "The server did not answer.", primary: "Retry" }], ["Hugs content (sheet)", { fills: false }]],
      tokens: [["paddingHorizontal", 24], ["icon.size", 40], ["icon.gap", 16], ["title.gap", 12], ["actions.gap", 16], ["action.spacing", 8], ["title (iOS)", "type.heading 16/22"], ["title (Android)", "titleLarge 20/28"], ["message", "type.bodySecondary"]],
      source: { ios: "Components/Feedback/OpenCodeEmptyState.swift", android: "ui/components/feedback/OpenCodeEmptyState.kt" },
      divergences: [
        { severity: "medium", title: "Title is 16pt on iOS and 20sp on Android" },
        { severity: "low", title: "Doc says there is no button, but both platforms support two" },
      ],
      render: emptyState },

    { id: "status-stack", doc: "status-stack", align: "stretch", cellHeight: 170,
      params: [sel("style", ["labeled", "spinner"]), text("title", "Loading sessions"), text("subtitle", "Checking server…"), sel("reveal", ["immediate", "afterGrace"])],
      states: [["Home copy", {}], ["Session copy", { title: "Loading messages", subtitle: "Fetching timeline…" }], ["Spinner", { style: "spinner", title: "Loading" }], ["After 300ms grace", { reveal: "afterGrace" }]],
      tokens: [["ring.size", 32], ["ring.stroke (Android)", 2.5], ["ring", "text-base"], ["track (Android)", "border-weaker-base"], ["ring.titleGap", 14], ["title.subtitleGap (iOS)", 14], ["title.subtitleGap (Android)", 4], ["paddingHorizontal", 24], ["revealGrace", "300ms"], ["reveal (iOS)", "easeOut 150ms"]],
      source: { ios: "Components/Feedback/OpenCodeStatusStack.swift", android: "ui/components/feedback/OpenCodeStatusStack.kt" },
      divergences: [
        { severity: "medium", title: "Title-to-subtitle gap is 14 on iOS and 4 on Android" },
        { severity: "low", title: "Grace reveal fades on iOS and appears instantly on Android" },
        { severity: "low", title: "Doc says single variant, but spinner and reveal exist" },
      ],
      render: statusStack },

    { id: "inline-notice", align: "stretch", pad: true,
      params: [text("message", "Selected model is unavailable."), sel("tone", ["neutral", "error"]), sel("variant", ["plain", "banner"]), bool("action"), text("actionTitle", "Retry")],
      states: [["Plain", {}], ["Plain error", { tone: "error", message: "Couldn't prepare diagnostic log for sharing." }], ["Banner", { variant: "banner", message: "Sessions stay in Recent while Running or Ready." }], ["Banner error", { variant: "banner", tone: "error", message: "12 entries dropped under writer pressure." }], ["Plain with action", { action: true, message: "Refresh failed: offline" }], ["Plain error with action", { action: true, tone: "error", message: "Refresh failed: offline" }], ["Banner with action", { action: true, variant: "banner", message: "Refresh failed: offline" }], ["Banner error with action", { action: true, tone: "error", variant: "banner", message: "Storage error: disk full." }]],
      sheet: [
        { title: "Plain", kind: "stack", inset: true, base: { variant: "plain" }, items: [["Neutral", {}], ["Error", { tone: "error", message: "Couldn't prepare diagnostic log for sharing." }], ["Neutral, action", { action: true, message: "Refresh failed: offline" }], ["Error, action", { action: true, tone: "error", message: "Refresh failed: offline" }]] },
        { title: "Banner", kind: "stack", inset: true, base: { variant: "banner" }, items: [["Neutral", { message: "Sessions stay in Recent while Running or Ready." }], ["Error", { tone: "error", message: "12 entries dropped under writer pressure." }], ["Neutral, action", { action: true, message: "Refresh failed: offline" }], ["Error, action", { action: true, tone: "error", message: "Storage error: disk full." }]] },
      ],
      tokens: [["row.spacing", 12], ["banner.padding", 12], ["banner.radius", 8], ["banner.neutral", "surface-raised-base"], ["banner.error", "error at 10%"], ["text.neutral", "text-base"], ["text.error", "error"], ["type", "type.caption"], ["action", "Button compactText, low"]],
      spec: "## Intent\nA short tonal message inside a screen, with an optional action.\n\n## Public API\n- `message` (required)\n- `tone`: `neutral`, `error`\n- `variant`: `plain` (type only), `banner` (padded tonal fill)\n- `action`: optional title and callback\n\n## Behavior\n- The message takes the remaining width and wraps.\n- The action is a low-contrast compact button on the same row, vertically centered.",
      source: { ios: "Components/Feedback/OpenCodeInlineNotice.swift", android: "ui/components/feedback/OpenCodeInlineNotice.kt" },
      divergences: [],
      render: inlineNotice },

    { id: "keyboard-hint", align: "stretch", pad: true,
      params: [text("text", "Press Tab to answer with the keyboard"), sel("tone", ["neutral", "warning"])],
      states: [["Neutral", {}], ["Warning", { text: "Esc again to interrupt", tone: "warning" }], ["Long text", { text: "Press Tab to answer with the keyboard, or Esc to leave this question open" }]],
      tokens: [["paddingHorizontal", 4], ["neutral", "text-weak"], ["warning", "warning"], ["type", "type.caption"], ["lines (iOS)", 1], ["lines (Android)", "unlimited"]],
      spec: "## Intent\nOne line of caption text about a hardware-keyboard key, shown only while it applies.\n\n## Public API\n- `text` (required)\n- `tone`: `neutral` for guidance, `warning` for a pending key action\n\n## Behavior\n- A 4pt inset aligns it with prompt and composer content.\n- iOS truncates to one line; Android wraps.\n- Android announces warning hints politely; iOS does not announce them.",
      source: { ios: "Components/Feedback/OpenCodeKeyboardHint.swift", android: "ui/components/feedback/OpenCodeKeyboardHint.kt" },
      divergences: [
        { severity: "high", title: "Warning hint is announced on Android but not on iOS" },
        { severity: "medium", title: "Long hints truncate on iOS and wrap on Android" },
      ],
      render: keyboardHint },
  ]);
})();
