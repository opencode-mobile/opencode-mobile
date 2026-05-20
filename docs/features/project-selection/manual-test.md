# Project Selection — Manual Test

1. Run `opencode serve` on `:4096` with at least two known projects in `GET /project`.
2. Open the app; confirm health probe then home loads. If nothing is added yet,
   confirm **Browse projects** and hint **Add a project to see recent sessions.**
   Probe failure should show **Unable to connect** with **Retry**.
3. Tap **Browse projects**; pick a second project; confirm a new section appears
   with up to three session previews (or **New Session** when empty).
4. Tap a session under a project; send a prompt; confirm messages load.
5. **See all...** opens a bottom sheet titled with the project name and the full
   session list for that worktree.
6. Top-bar **widgets** action (accessibility **Developer components**) opens
   Component Gallery on both platforms.
7. Add projects A then B; leave home and return (or relaunch) several times; sections
   stay in add order on **iOS and Android** (not network-completion order).
8. Pull to refresh / relaunch; confirm added projects persist.
