# opencode-mobile context

`opencode-mobile` is a native iOS and Android client for controlling sessions
on a user-managed opencode server.

This file defines stable product vocabulary. It does not describe current
implementation status, feature plans, or detailed UI behavior.

## Product context

The server normally runs on the user's development machine. A mobile client
reaches it through a user-managed LAN, VPN, HTTPS endpoint, or tunnel. Requests
can target an added project by sending its worktree path as `directory`.

## Glossary

### opencode server

The HTTP server that exposes opencode projects, sessions, messages, events, and
control operations to the mobile client.

### Connection Profile

A saved configuration for one reachable opencode server.

It contains the base URL, username, securely stored password, and ordered list
of added-project worktree paths. It describes how the client reaches a server,
not a user account.

### Connection onboarding

The first-run screen shown when no Connection Profile exists.

It is distinct from the unavailable state shown when a saved server cannot
currently be reached.

### added project

A project worktree the user chose to show for a Connection Profile.

Adding a project is local curation. It does not create or modify a project on
the server.

### current project

The project context used for a server request.

With `directory={worktree}`, it means that worktree. Without `directory`, it
means the server process boot worktree.

### Terminal

A live shell on the connected opencode server for an added project.

It is not a session and is not part of Session Chat.

_Avoid_: PTY as a product label, SSH, TUI attach, session shell command.

### session

A conversation and work stream managed by opencode for the current project.

### child session

A session created from a parent session to perform delegated work.

_Avoid_: subagent as the product name.

### archive

A session action that removes a session from active lists while retaining it on
the server.

_Avoid_: delete, hide, dismiss.

### delete

A session action that permanently removes a session and its associated data.

_Avoid_: archive, remove when archive is meant.

### message

A user or assistant item in a session timeline. A message contains ordered,
typed message parts.

### message part

A typed content item inside a message, such as text, reasoning, a tool call, or
a file.

### Attachment

A device file included with a prompt and represented as a file part after send.

_Avoid_: upload when no separate upload operation exists, project file context,
agent mention.

### Session Todo List

The agent-owned, view-only work list for one session.

_Avoid_: user todo list, checklist, Plan.

### Session Todo

One ordered item in a Session Todo List. Its status is `pending`,
`in_progress`, `completed`, or `cancelled`.

### server command

A named command from the server catalog that runs against a session instead of
being sent as a normal prompt.

_Avoid_: client slash action, slash command when the distinction matters.

### client slash action

A curated local action in Session Chat, such as new session, fork, undo, redo,
or compact.

It is not loaded from the server command catalog.

### mentionable agent

An agent from the server catalog that can be referenced with `@` without
becoming the selected turn-owning agent.

_Avoid_: subagent.

### agent mention

A structured reference to a mentionable agent in a prompt.

It does not change the selected agent and is not an Attachment.

### Model Selection

The provider and model selected for the next prompt, with an optional
server-provided Variant.

### Variant

A server-provided model behavior or reasoning option. `Default` means the
client sends no explicit variant.

_Avoid_: Thinking Effort as the general product label.

### New Session Model Preference

The Model Selection explicitly chosen on this device as the starting choice for
new sessions in the same Connection Profile and added project.

### permission request

A request for the user to approve or deny an action before the session can
continue.

### question request

A request for one or more structured answers before the session can continue.

It is not a permission request.

### Change Set

A read-only collection of file diffs selected for review. Sources can include
the last turn, working tree, or current branch relative to its default branch.

### diff

The line-level representation of one changed file in a Change Set.

### Rendered Markdown

Formatted display of message text. Raw markdown remains the source for sending,
copying, and restoring text to the composer.

### Session Context

A session-scoped inspection view for model context, token usage, message
accounting, and cost.

### foreground event stream

The server-sent event connection used while the app is active. The client
refreshes after reconnect instead of promising background delivery.
