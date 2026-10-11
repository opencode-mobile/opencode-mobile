---
name: publish-prototype
description: "Prototype publishing: use when a browser prototype must be built and shared over LAN or Tailscale."
---

# Publish prototype

1. Build the prototype with the user-level `prototype` skill
   (`~/.agents/skills/prototype/SKILL.md`). When it shows a phone bezel or
   device frame, apply the **Phone mockup layout (exact-spec)** section of the
   user-level `publish-artifact` skill: one viewport with no page scroll,
   controls beside the phone on wide screens, the device scaled to fit height,
   and only the inner screen scrolling.
2. Publish the output with the `publish-artifact` skill
   (`~/.agents/skills/publish-artifact/SKILL.md`).
3. Paste the returned LAN and Tailscale URLs.

Both skills live on the developer's machine, not in this repository. If one is
missing, stop and report it rather than publishing another way.
