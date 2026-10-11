---
name: publish-ui-snapshots
description: "UI snapshots: use when the user asks to generate and publish isolated or in-session snapshot evidence."
---

# Publish UI snapshots

Trigger phrase: "create ui snapshots to illustrate the changes and publish
artifacts".

Defaults: subject = assistant message; platforms = iOS and Android; frames =
`isolated` and `in-session`; appearance = light, plus dark when cheap.

1. **Render hosts.** Reuse the committed mocks and hosts under
   `ios/OpenCode Mobile/OpenCode Mobile/SnapshotEvidence/` and Android
   `ui/snapshot/`. Use deterministic mock data; never screenshot a live server.
   If the subject has no mock, add a small committed one or stop and name the
   gap.
2. **Throwaway tests.** Write them into the gitignored generated paths in
   [reference.md](reference.md) using its templates. Do not edit
   `project.pbxproj`.
3. **Generate PNGs** with the platform recipe in `reference.md`. Record only;
   do not verify goldens, run UITests, or run `verifyPaparazziDebug`.
4. **Report.** Copy PNGs to `.snapshot-evidence/out/` as
   `{platform}-{frame}-{appearance}.png` (e.g. `ios-isolated-light.png`) and run:

   ```bash
   bash "$REPO/.agents/skills/publish-ui-snapshots/scripts/write-report.sh" \
     "$REPO/.snapshot-evidence/out" "UI snapshots"
   ```

5. **Publish** `.snapshot-evidence/out/` with the user-level `publish-artifact`
   skill (`~/.agents/skills/publish-artifact/SKILL.md`; stop and report if
   missing) using `--type plan`, and paste the returned URLs.

Throwaway tests, PNGs, HTML, and Paparazzi goldens stay out of commits; commit
durable mock or host changes only when asked. Remove the generated iOS test
directory when you are done, since stale files there can break later test
builds.
