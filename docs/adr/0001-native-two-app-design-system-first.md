# ADR 0001: Native Two-App Architecture With Design-System-First Development

## Status

Accepted

## Context

`opencode-mobile` will remotely control opencode sessions from iOS and Android.
The v1 product is a focused foreground session-control client, not a mobile
terminal emulator, mobile IDE, relay service, or multi-project manager.

The project needs a future architecture that supports native user experience on
both platforms while keeping product behavior, design language, and feature
progress aligned. Prior testing showed that building two apps together works
best when the reusable UI component collection is built first and kept in sync.

Plausible alternatives were:

- a cross-platform framework with one shared UI implementation;
- building one native app first and porting the second later;
- building feature screens first and extracting a component collection later;
- allowing each platform to evolve independently and reconciling differences
  near release.

## Decision

Build two native apps:

- iOS: SwiftUI.
- Android: Kotlin with Jetpack Compose and Material 3.

Keep code physically native per platform, but align the conceptual architecture:

- generated OpenAPI clients wrapped by a domain adapter;
- repositories or services that expose product operations;
- Screen Models that expose immutable UI state and explicit actions;
- screens composed from a shared Design System and Component Collection.

Develop reusable UI before feature screens depend on it. A design-system
component slice should define the shared intent, tokens, typography, variants,
states, platform mappings, previews, and any intentional divergence.

Develop product features as synchronized Feature Slices across iOS and Android.
Document progress, decisions, and Parity Gaps as part of the same work.

## Consequences

This keeps each app native and gives each platform room to follow its own UI and
system conventions.

The cost is coordination. Every shared component and feature requires explicit
parity work, documentation, and gap tracking. The repository must maintain
architecture docs, design-system docs, feature progress docs, and project-local
skills that guide agents through the workflow.

This decision also rejects using generated server schemas directly in feature
code. Generated clients may change with the opencode server contract, while the
mobile app should preserve stable product language through a domain adapter.

## Review Triggers

Revisit this decision if:

- native implementation cost becomes the main blocker to shipping v1;
- the two apps repeatedly fail to stay aligned despite the documented workflow;
- opencode introduces an official shared mobile SDK or design-system contract;
- v1 scope expands into areas that require a backend, relay, push service, or
  reliable background synchronization.
