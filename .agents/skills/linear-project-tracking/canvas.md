# Work Plan Canvas Template

Reference for `linear-project-tracking` canvases. Follow the `canvas` skill for
SDK rules and design constraints.

## File path

```text
~/.cursor/projects/<workspace>/canvases/<issue-key>-<slug>-work-plan.canvas.tsx
```

## Sections (top to bottom)

1. **Header** — `H1` with issue title, status `Pill`, secondary line with
   branch, milestone, docs path
2. **Stats row** — `Grid` of 3–4 `Stat` components (checklist %, progress
   items, todos done, phases left)
3. **Current focus** — `Callout` with tone matching state (`info` in progress,
   `success` when done, `warning` when blocked)
4. **Optional progress chart** — `BarChart` when multiple areas/platforms have
   countable items from `progress.md`
5. **Implementation phases** — `Card` per phase with platform `Pill`, status
   `Pill`, bullet items, files touched
6. **Execution todos** — `TodoListCard` with `defaultExpanded`; statuses:
   `pending`, `in_progress`, `completed`
7. **Close-out** — manual QA table, out-of-scope notes, Linear sync reminder

## Data conventions

Embed all data inline. Update constants when progress lands:

```tsx
const ISSUE_KEY = "OPE-17";
const BRANCH = "theme/ope-17-persistence-and-import";
const MILESTONE = "Polish & Validation";
const DOCS = "docs/features/theme/";
```

Phase status maps to `Pill` tone:

| Status | Pill tone | TodoStatus |
| --- | --- | --- |
| Not started | neutral | `pending` |
| Active | info | `in_progress` |
| Done | success | `completed` |

## Minimal starter skeleton

```tsx
import {
  Callout,
  Card,
  CardBody,
  CardHeader,
  Grid,
  H1,
  H2,
  Pill,
  Row,
  Stack,
  Stat,
  Text,
  TodoListCard,
} from "cursor/canvas";

const ISSUE_KEY = "OPE-XX";
const BRANCH = "feature/ope-xx-slug";
const MILESTONE = "…";
const DOCS = "docs/features/<slug>/";

const phases = [
  {
    id: "p1",
    title: "Phase 1 — …",
    status: "in_progress" as const,
    platform: "iOS + Android",
    items: ["…"],
    files: "…",
  },
];

const todos = [
  { id: "t1", content: "Read progress.md + set Linear In Progress", status: "completed" as const },
  { id: "t2", content: "Phase 1: …", status: "in_progress" as const },
  { id: "t3", content: "Update progress.md + Linear", status: "pending" as const },
];

export default function WorkPlan() {
  const todosDone = todos.filter((t) => t.status === "completed").length;
  const phasesLeft = phases.filter((p) => p.status !== "completed").length;

  return (
    <Stack gap={20} style={{ padding: 24, maxWidth: 960 }}>
      <Stack gap={6}>
        <Row gap={8} align="center">
          <H1>{ISSUE_KEY} Feature Name</H1>
          <Pill tone="info">In progress</Pill>
        </Row>
        <Text tone="secondary">
          Branch: {BRANCH} · Milestone: {MILESTONE} · Docs: {DOCS}
        </Text>
      </Stack>

      <Grid columns={3} gap={12}>
        <Stat value={`${todosDone}/${todos.length}`} label="Execution todos" tone="info" />
        <Stat value={String(phasesLeft)} label="Phases left" />
        <Stat value="—" label="Checklist" />
      </Grid>

      <Callout tone="info">Current focus: Phase 1 — …</Callout>

      <H2>Implementation phases</H2>
      <Stack gap={12}>
        {phases.map((phase) => (
          <Card key={phase.id}>
            <CardHeader trailing={<Pill size="sm">{phase.platform}</Pill>}>
              {phase.title}
            </CardHeader>
            <CardBody>
              <Stack gap={8}>
                {phase.items.map((item, i) => (
                  <Text key={i} size="small">{item}</Text>
                ))}
                <Text tone="tertiary" size="small">Touch: {phase.files}</Text>
              </Stack>
            </CardBody>
          </Card>
        ))}
      </Stack>

      <Card>
        <CardHeader trailing={<Pill size="sm">{todosDone} of {todos.length} done</Pill>}>
          Execution todos
        </CardHeader>
        <CardBody>
          <TodoListCard todos={todos} defaultExpanded />
        </CardBody>
      </Card>
    </Stack>
  );
}
```

## Live example

See `ope-17-theme-work-plan.canvas.tsx` in the workspace `canvases/` directory
for a completed reference.

## Update cadence

| Trigger | What to change |
| --- | --- |
| Start work | Create file; seed phases/todos from `progress.md` |
| Phase done | Phase status → `completed`; matching todos → `completed`; refresh stats |
| New scope | Add phase/todo row; update callout |
| Session end | Ensure callout reflects next step |
| Issue done | All phases/todos complete; callout → QA or "Ready to close" |
