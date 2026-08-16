# OPE-170 Lock child session composer

## User goal

A child session is read-only. The composer shows the model and thinking
effort used there, and a primary action returns to the main session.

## In scope

- Design-system Session Composer `childLockout` state (variant A).
- Display-only model and effort chips; no text field, attach, commands, send,
  or stop.
- Caption: “Child sessions cannot be prompted.”
- Primary button: “Back to main session” (existing leave/back path).
- Permission and question still replace the lockout when pending.
- iOS and Android Session Chat wiring from `parentID` / `parentId`.

## Out of scope

- Prompting a child session from mobile.
- Changing how child sessions are created or listed.
- Mode chip on the lockout (model + effort only).
