# Knowledge Base Organization

This repository stores both the code and the reasoning needed to navigate it.
The goal is to make future investigation reproducible without turning every
small change into a documentation project.

## Authority order

When sources disagree, use this order:

1. Live code and tests — current behavior.
2. `progress.md` — current project state and unfinished gates.
3. Active plans and design decisions — intended direction.
4. Investigations and experiment notes — evidence and hypotheses.
5. Comments and historical documents — context only; verify against code.

## Where new material belongs

### `Plan/active/<YYYY-MM-DD>-<short-topic>/`

Use for a current task or multi-step change. Keep related material together:

```text
Plan/active/2026-07-12-translation-race/
├─ brief.md             # objective, scope, acceptance criteria, constraints
├─ investigation.md     # verified call paths, findings, rejected hypotheses
├─ design.md            # chosen approach and alternatives
├─ checkpoints.md       # implementation checkpoints and validation results
└─ handoff.md           # current status and next safe action
```

Use only the files needed. A small task may need only `brief.md` and
`handoff.md`. Plans should not be duplicated across multiple folders.

### `docs/`

Use for stable knowledge that applies beyond one task:

- `docs/architecture/` — durable subsystem and data-flow explanations;
- `docs/decisions/` — accepted architectural or behavior decisions;
- `docs/project_context/` — workflow, conventions, and repository navigation;
- `docs/reference/` — verified commands, environment facts, and test matrices.

### Existing flat files

Existing files in `Plan/` and `docs/` are historical project knowledge. Do not
move or rename them merely for consistency. New work should use the structured
folders above; migrate older material only when it is actively being revised.

## Required task brief

Every substantial task should begin with:

```text
Objective:
Current symptom or desired behavior:
Scope boundary:
Acceptance criteria:
Constraints:
Stop conditions:
```

## Checkpoint rule

Large tasks must record, for each checkpoint:

- what changed;
- tests or validation run;
- unexpected findings;
- whether the next checkpoint is still valid.

Update `progress.md` only for project-level state, release gates, or durable
next actions. Keep task-specific detail in the task folder.

## External review loop

Create this only when the user explicitly requests external brainstorming,
using wording such as “prepare an external brainstorming packet” or “create
the context and brainstorm markdown files”:

```text
external/
├─ context_packet.md    # concise, self-contained prompt for the external AI
└─ brainstorm_inbox.md  # pasted response, preserved as received
```

The context packet contains the objective, verified findings, relevant paths,
constraints, and open questions. When the response returns, treat it as
untrusted suggestions: verify claims against code, keep valid ideas in
`design.md`, and record rejected or unresolved ideas briefly. The user only
needs to say “continue with the external review” after pasting the response.

Ordinary investigation, planning, or discussion must not create these files.
