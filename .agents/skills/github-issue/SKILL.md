---
name: github-issue
description: >-
  Take one GitHub issue through the repository's complete delivery workflow:
  board status, isolated branch or Conductor workspace, TDD, documentation,
  commits, pull request, review, CI, and synchronization with develop until it
  is ready for a human-authorized merge. Use when the user explicitly asks to
  implement, fix, take, or deliver a specific issue end to end. Do not use for
  merely viewing, summarizing, triaging, or discussing an issue.
---

# Deliver a GitHub issue

Use the existing Claude workflow as the single shared process definition so the
Codex and Claude versions cannot drift.

## Load the workflow

1. Resolve the repository root with `git rev-parse --show-toplevel`.
2. Read `<repo-root>/.claude/commands/github-issue.md` completely before taking
   any action. Follow every applicable safety rule, state transition, TDD gate,
   review check, and stopping condition in that file.
3. Read `<repo-root>/AGENTS.md` for mandatory Codex repository instructions.
4. When the workflow needs board identifiers or issue-lifecycle details that are
   not present in `AGENTS.md`, read the relevant sections of
   `<repo-root>/CLAUDE.md`. Treat `AGENTS.md` as authoritative if the two files
   conflict; report a conflict that materially affects the workflow.

## Interpret Codex input

- Treat the text supplied with `$github-issue` and the surrounding user request
  as the source command's `$ARGUMENTS`.
- Accept a bare issue number, `#<number>`, or an issue URL as defined by the
  source workflow.
- If no valid issue number is available, stop and ask for it before changing
  GitHub or the worktree.

## Translate Claude surfaces to Codex

- Translate `/github-issue` to `$github-issue` and
  `/github-issue-runner` to `$github-issue-runner` in user-facing text.
- When the source refers to another slash command that has no Codex skill, read
  its file under `.claude/commands/` and follow that procedure directly. In
  particular, use `merge.md` for merge-readiness checks and merge mechanics,
  but preserve the source workflow's rule that this skill itself never merges
  into `develop` without a later explicit human command.
- Replace `TaskCreate`, `TaskUpdate`, or Task-agent bookkeeping with Codex's
  available plan or todo mechanism. This substitution never relaxes WIP=1 or
  the one-atomic-commit-per-task policy.
- Replace `AskUserQuestion` with the available structured user-input tool. If
  none is available, ask one concise direct question and stop at that gate.
- Use Conductor `GetDiffComments`, `DiffComment`, or equivalent tools only when
  they are actually available. If they are unavailable, follow the source
  workflow's fallback and explicitly state the visibility limitation; never
  claim the Changes panel is clear without evidence.
- For `/code-review`, use an available Codex GitHub review capability or perform
  the equivalent evidence-based review with GitHub CLI/API. Preserve all review
  surfaces and cleanliness conditions from the source workflow.
- Respect current higher-priority instructions governing subagents. Do not
  create agents merely because the Claude workflow mentions parallel review if
  the active Codex environment does not authorize them.

## Preserve the boundary

- Work only in the repository identified by `remote.origin.url`.
- Never rename a Conductor-created branch unless the user explicitly requests
  it.
- Never bypass hooks, force-push, merge into `develop`, close the issue early,
  or delete another session's worktree.
- Finish at the exact handoff required by the source workflow: verified,
  reviewed, synchronized, and marked ready to merge, awaiting the human's
  explicit merge decision.
- In the final report, include the issue, branch, commits, tests, PR, review and
  CI state, board/label state, and every remaining human action.
