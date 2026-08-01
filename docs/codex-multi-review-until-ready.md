# Codex multi-review until ready

`$multi-review-until-ready` reviews the current candidate change with four
independent Codex subagents, fixes validated findings, and repeats the complete
review until all four approve the same snapshot.

The command always runs in the current Codex session's Git branch and worktree.
It does not create, switch, or rename branches. `BASE` only chooses the ref used
for comparison; it never changes the branch being reviewed or fixed.

Invoke it from a Codex chat in the repository:

```text
$multi-review-until-ready
$multi-review-until-ready BASE=origin/develop
$multi-review-until-ready BASE=origin/develop FOCUS="device authorization and refresh-token rotation"
```

The default base is the environment's target branch, falling back first to
`origin/develop` and then to the repository's remote default branch. Therefore
a plain `$multi-review-until-ready` reviews the branch in which the current
session is running against that base, including its committed, staged,
unstaged, and untracked changes.

The four project-scoped reviewers cover functional correctness, security and
data integrity, tests and reliability, and architecture and compatibility.
Their profiles request a read-only sandbox and their contract forbids edits;
snapshot checks reject the complete round if the shared worktree changes while
they inspect it. The primary agent verifies and deduplicates their findings,
implements fixes under the repository's TDD rules, runs the applicable gates,
and starts four fresh reviewers after every fix set. Approval is unanimous but
not vote-based: one validated defect blocks the round.

The command ends with `READY_TO_MERGE` only when all four reviewers return that
verdict for the same unchanged snapshot and all required deterministic checks
are green. This is a review result, not permission to push or merge; the skill
does neither without a separate explicit request.

The skill is intentionally explicit-only because a complete run can consume
substantial time and tokens. If it does not appear after pulling the files,
restart Codex or open a new conversation.
