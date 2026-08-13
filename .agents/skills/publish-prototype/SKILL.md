---
name: publish-prototype
description: Build a throwaway prototype to answer a design question, then publish it to the artifact server so it can be viewed remotely. Use when the user wants a prototype they can open in a browser over LAN / Tailscale.
---

# publish-prototype

One skill, two steps: build the prototype, then publish it.

## When to use
The user wants a prototype (logic or UI) that ends up viewable in a browser remotely.

## How to do it
1. Load the **prototype** skill and build the prototype per its rules (`~/.agents/skills/prototype/SKILL.md`).
2. Load the **publish-artifact** skill and publish the built output per its rules (`~/.agents/skills/publish-artifact/SKILL.md`).
3. Paste the LAN and Tailscale URLs the publish step prints so the user can open it.