# Model Selection

## User Goal

Choose the model and optional model variant for the next Session Chat prompt
from the connected opencode server's configured providers.

## In Scope

- Fetch `GET /config/providers?directory={worktree}` for the open project.
- Show usable provider/model options in Session Chat.
- Initialize selection from the latest message model/variant when available,
  then server default, then first usable model.
- Remember user changes while the Session Chat screen remains open.
- Send `model: { providerID, modelID }` and a non-default top-level `variant`
  through `POST /session/{sessionID}/prompt_async`.

## Out Of Scope

- Editing opencode model defaults or provider configuration.
- Managing provider auth.
- Persisting model choice across app launches or sessions.

## Server APIs Involved

- `GET /config/providers`
- `POST /session/{id}/prompt_async`
- `GET /session/{id}/message`

## Domain Objects

- **Model Selection Catalog:** server-backed list of usable model options plus
  the server default.
- **Model Selection Option:** provider id/name, model id/name, and available
  variant keys.
- **Model Selection:** selected model option plus optional selected variant.

## Screen Model State

- Selected model and optional selected variant.
- Model option rows for the picker.
- Variant rows: `Default` plus the selected model's variant keys.
- Non-blocking notice when a remembered model is unavailable and a fallback is
  selected.

## Design-System Components

Session Chat reuses existing `OpenCodeChoiceBadge` and `OpenCodeBottomSheet`.
The picker layout remains feature-local because it is specific to this composer
flow and does not introduce a new reusable primitive.
