# Codex GitHub issue skills

Codex exposes the repository's two GitHub issue delivery workflows as
repository-scoped skills:

- `$github-issue <number>` takes one issue through board transitions, isolated
  implementation, TDD, documentation, PR creation, review, CI, and
  synchronization with `develop`. It stops when the PR is ready for an explicit
  human merge decision.
- `$github-issue-runner [arguments]` coordinates up to three issues, prevents
  overlapping work, monitors executors, verifies readiness independently, and
  serializes authorized merges. It must be invoked explicitly because invoking
  it grants merge authority for the issues selected into that run.

The skills live under `.agents/skills/`, which makes them available to Codex
for this repository. Their detailed process remains in the existing
`.claude/commands/github-issue.md`,
`.claude/commands/github-issue-runner.md`, and `.claude/commands/merge.md` files.
Each Codex skill reads those files at runtime and provides the compatibility
rules for Codex plans, user-input gates, reviews, Conductor diff comments, and
subagents. Keeping one process definition prevents the Claude and Codex
workflows from drifting.

If a newly added skill does not appear immediately in the Codex skill selector,
restart Codex or open a new conversation.
