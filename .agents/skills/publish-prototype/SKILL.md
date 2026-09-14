---
name: publish-prototype
description: "Prototype publishing: use when a browser prototype must be built and shared over LAN or Tailscale."
---

# Publish prototype

## 1. Build the prototype

Load the `prototype` skill from
`~/.agents/skills/prototype/SKILL.md` and build the requested logic or UI.

When the prototype has a phone bezel or device frame, apply
`publish-artifact`'s **Phone mockup layout (exact-spec)** while building the
HTML:

- Use one viewport with no page scroll. Set `overflow: hidden` on the outer page.
- Put scenario controls beside the phone on wide screens. Stack them only on
  narrow viewports.
- Scale the whole device to fit height and preserve its aspect ratio.
- Allow the inner screen to scroll while the page around the phone stays fixed.

The full phone CSS pattern is the **Phone mockup layout (exact-spec)** section
of `~/.agents/skills/publish-artifact/SKILL.md`.

Both `prototype` and `publish-artifact` are user-level skills on the
developer's machine, not part of this repository. If either is missing, stop
and report it; do not substitute an ad-hoc publish path.

**Completion criterion:** The prototype answers the requested design question,
runs in a browser, and every phone mockup satisfies the exact layout rules.

## 2. Publish the artifact

Load the `publish-artifact` skill from
`~/.agents/skills/publish-artifact/SKILL.md` and publish the built output.

**Completion criterion:** Publication succeeds and returns both LAN and
Tailscale URLs.

## 3. Report access

Paste the LAN and Tailscale URLs so the user can open the prototype.

**Completion criterion:** The response contains both published URLs exactly as
returned by the publication step.
