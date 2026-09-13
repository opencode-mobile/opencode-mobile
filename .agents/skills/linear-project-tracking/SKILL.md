---
name: linear-project-tracking
description: Read and update Linear when the user asks about issues, backlog, or ticket refinement. Linear owns ticket detail.
---

# Linear Project Tracking

**Linear owns the ticket.** Description, acceptance, remaining work, status,
refinements, and temporary platform gaps live on the issue. Do not copy ticket
state into repository documentation.

Do not create or update repository documentation while working on a ticket
unless the user explicitly requests documentation.

Use the available Linear app or connector. If Linear is unavailable, say so
and do not invent issue state.

## When the user asks about a ticket

1. Read the Linear issue (and parent/children if needed).
2. Answer from Linear.
3. If they refine the ticket, update **Linear**, not markdown.

## When implementing from a ticket

1. Read the Linear issue first.
2. Use code, tests, and the issue's acceptance criteria as the current
   implementation contract.
3. After the work ships, update Linear status when that mutation is requested.

## When the user asks to create or split tickets

Create or update issues in Linear. Do not add ticket-plan files to the repo.

Workspace conventions live in [reference.md](reference.md).
