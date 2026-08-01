---
name: github-issue-runner
description: >-
  Coordinate a bounded queue of GitHub issues through the repository's delivery
  pipeline, selecting and ordering work, preventing overlapping changes,
  monitoring executors, verifying pull requests, and serializing merges into
  develop. Use only when the user explicitly invokes this skill to run multiple
  backlog or ready issues; invocation grants merge authority only for the issues
  selected into that run. Do not use for single-issue work, passive backlog
  summaries, or general triage.
---

# Run the GitHub issue queue

Use the existing Claude workflow as the single shared process definition so the
Codex and Claude versions cannot drift.

## Load the workflow

1. Resolve the repository root with `git rev-parse --show-toplevel`.
2. Read `<repo-root>/.claude/commands/github-issue-runner.md` completely before
   taking any action. Follow every scheduling, overlap, authorization,
   verification, merge, stop, and reporting rule in that file.
3. Read `<repo-root>/.agents/skills/github-issue/SKILL.md` and then
   `<repo-root>/.claude/commands/github-issue.md` completely because each
   executor must follow the single-issue delivery contract.
4. Read `<repo-root>/AGENTS.md` for mandatory Codex repository instructions.
5. When the workflow needs board identifiers or issue-lifecycle details that are
   not present in `AGENTS.md`, read the relevant sections of
   `<repo-root>/CLAUDE.md`. Treat `AGENTS.md` as authoritative if the two files
   conflict; report a conflict that materially affects the run.

## Interpret Codex input

- Treat the text supplied with `$github-issue-runner` and the surrounding user
  request as the source command's `$ARGUMENTS`.
- Preserve the source meanings for an empty argument, a window size, explicit
  issue numbers, and the `agents` mode flag.
- Because starting this skill authorizes merges for its selected run, accept
  that authority only from an explicit `$github-issue-runner` invocation. Do
  not infer it from a general request to inspect or summarize the backlog.

## Translate Claude surfaces to Codex

- Translate `/github-issue` to `$github-issue` and
  `/github-issue-runner` to `$github-issue-runner` in user-facing text.
- When the source invokes `/merge`, read `.claude/commands/merge.md` completely
  and execute its checks and merge procedure directly. The explicit runner
  invocation supplies the per-run merge authorization described by the source;
  it does not authorize unrelated PRs or weaken any readiness gate.
- Replace `TaskCreate`, `TaskUpdate`, or dispatcher bookkeeping with Codex's
  available plan or todo mechanism.
- Replace `AskUserQuestion` with the available structured user-input tool. If
  none is available, ask one concise direct question and stop at that gate.
- Use Conductor diff-comment tools only when available. In the default
  Conductor mode, require each executor to confirm its own Changes panel is
  clear exactly as the source workflow specifies.
- For code review, use available Codex GitHub review capabilities or an
  equivalent evidence-based GitHub CLI/API review. Preserve unresolved-thread,
  pending-review, requested-changes, CI, and diff-comment gates.
- Spawn subagents only when the source workflow selects agent mode and current
  higher-priority instructions allow it. Otherwise use the default Conductor
  dispatcher mode: provide workspace names and first prompts to the human, then
  monitor external progress without writing code in those workspaces.

## Preserve the boundary

- Keep the active window at three issues or fewer and never add a mid-run
  discovery to it.
- Work only in the repository identified by `remote.origin.url`.
- The dispatcher coordinates and verifies; it does not edit executor code or
  commit to executor branches.
- Serialize merges. After every merge, re-fetch and completely re-evaluate each
  remaining PR, including Flyway, protobuf field-number, and spec-directory
  collisions that Git cannot detect.
- Never use force/admin bypasses or treat an executor's report as evidence
  without independently checking GitHub state.
- Stop on every human-decision condition in the source workflow.
- In the final report, list each selected issue, execution location, dependency
  order, current state, PR and merge result, board/label state, cleanup, and all
  remaining human actions.
