---
name: multi-review-until-ready
description: >-
  Run an iterative multi-agent review of the current branch or working tree,
  aggregate independent findings, fix validated defects, run repository gates,
  and repeat fresh review rounds until every reviewer returns READY_TO_MERGE for
  the same snapshot. Use only when the user explicitly invokes
  $multi-review-until-ready or explicitly asks for this review-fix-rereview loop.
  Do not use for a read-only review, a quick review, or a request that does not
  authorize code changes.
---

# Review until every reviewer approves

Own the complete loop. Reviewers only inspect; the primary agent validates,
fixes, tests, and orchestrates. Never merge, push, open a PR, resolve a remote
review thread, or mark an issue ready without separate authorization.

## Interpret input

- Accept `BASE=<git-ref>` and optional `FOCUS="<area>"` from the invocation.
- Always operate in the branch and worktree of the current Codex session. By
  default, this is the branch from which the user invoked the skill. Never
  create, switch, rename, or check out another branch for this workflow.
- Default `BASE` to the target branch supplied by the environment, otherwise
  `origin/develop` when it exists, otherwise the repository's remote default.
- When the environment supplies a local branch name and the corresponding
  `origin/<name>` exists, prefer that remote-tracking ref for comparison.
- Use `BASE` only as the comparison ref. It never selects the working branch.
- Treat remaining text as additional review focus, never as permission to
  broaden the changed-code scope.
- Review committed, staged, unstaged, and untracked changes relative to `BASE`.
  Include supporting unchanged code when needed to prove a finding.
- Flag only defects introduced or exposed by the candidate change. Report an
  unrelated pre-existing defect separately; do not make it block approval.

## Prepare the review

1. Read every applicable `AGENTS.md` and repository review instruction.
2. Confirm the workspace, current session branch, base ref, worktree status,
   and diff scope. Keep every review and fix in that current worktree.
3. Preserve pre-existing user changes. Never discard, overwrite, or silently
   include unrelated work in a fix or commit.
4. Determine the repository's required validation commands from its
   instructions and changed areas.
5. Run `python3 <skill-dir>/scripts/snapshot.py` to record the identity of
   `HEAD`, the index, tracked contents, untracked contents, and submodules. Give
   reviewers both the identity and this exact command so they can recompute it.
   If the candidate changes while reviewers run, discard every verdict from
   that round and restart it.

## Run one review round

Start fresh subagents with no inherited conversation turns (`fork_turns="none"`
when supported) for all four project agents below. Run as many in parallel as
the current concurrency limit permits, then start the remaining reviewers as
slots become available. Do not pass earlier findings or verdicts to later
reviewers. A missing, failed, interrupted, or incomplete reviewer is not an
approval.

| Project agent | Required focus |
|---|---|
| `correctness_reviewer` | Runtime logic, state, errors, concurrency, boundaries |
| `security_data_reviewer` | Auth, tenancy, untrusted input, transactions, data integrity |
| `test_reliability_reviewer` | Regression proof, failure modes, flakiness, operational reliability |
| `architecture_contract_reviewer` | Repository rules, layers, compatibility, migrations, cross-layer contracts |

Select the matching custom agent profile when the spawn surface supports it.
Otherwise spawn a generic subagent with the same task name and include the
table's focus in its prompt. The inline contract remains authoritative.

Give each reviewer only the base ref, snapshot identity, user focus, applicable
instructions, and this contract:

- Inspect the full candidate diff independently. Read surrounding code, tests,
  history, or documentation as needed.
- Do not edit files, create commits, or trust another reviewer's conclusion.
- Verify the supplied snapshot identity immediately before and after inspection.
  Return `VERDICT: STALE_SNAPSHOT` instead of a review verdict if either value
  differs. Include both observed identities in every response.
- Prefer concrete, actionable defects over style preferences.
- Return findings in this exact shape:

  ```text
  SNAPSHOT_START: <identity>
  SNAPSHOT_END: <identity>
  VERDICT: CHANGES_REQUIRED
  FINDINGS:
  - ID: <role>-<number>
    SEVERITY: P0 | P1 | P2 | P3
    LOCATION: <file>:<line>
    PROBLEM: <specific defect>
    EVIDENCE: <execution path, reproduction, or violated invariant>
    IMPACT: <user or system consequence>
    FIX: <smallest safe correction>
    TEST: <test that would prove the correction>
  ```

- If and only if there are no actionable findings, return exactly:

  ```text
  SNAPSHOT_START: <identity>
  SNAPSHOT_END: <same identity>
  VERDICT: READY_TO_MERGE
  FINDINGS: []
  ```

Do not reuse reviewer threads between rounds. Fresh threads must reassess the
new state rather than defend earlier findings.

## Aggregate and adjudicate

Wait for every reviewer. Normalize and deduplicate findings by root cause, not
wording. Never use majority voting: one validated finding blocks the round even
if the other reviewers approve.

First compare all start/end identities with the parent's current identity. Any
mismatch, reviewer mutation, or external edit invalidates the complete round.
Inspect unexpected changes and preserve user work; never silently revert them.

For each finding:

1. Inspect the cited path and verify the execution path or invariant yourself.
2. Reproduce the failure with a focused test or command when practical.
3. Classify it as validated, duplicate, non-blocking pre-existing, or false
   positive, and retain concise evidence for that decision.
4. Treat uncertainty about a material behavior or contract as unresolved, not
   as approval.

## Fix validated findings

Fix validated findings serially in the primary thread. Follow repository TDD
and documentation policy; add a failing regression test before production code
when the behavior is testable. Make the smallest coherent fix and preserve
public APIs, migrations, generated artifacts, and unrelated user changes.

Run focused checks while iterating, then all repository-required gates for the
changed areas. Respect repository commit policy, but never absorb unrelated
pre-existing changes into a review-fix commit. Do not bypass hooks or tests.

Any content change immediately invalidates all approvals. Finish the current
round's coherent TDD fix set and applicable gates, then start a completely new
round with four fresh reviewers. Do not restart between the failing regression
test and its production fix. Do not ask reviewers to examine only the last fix:
they must review the full current candidate against `BASE` for regressions and
interactions.

Any `CHANGES_REQUIRED` verdict also requires another fresh round after
adjudication, even if every reported item was a duplicate or false positive.
Only a round in which all four reviewers themselves approve can succeed.

## Stop conditions

Continue without an arbitrary round limit while useful progress is possible.
Pause and ask the user only when a material product decision, unavailable
dependency, unsafe external action, irreconcilable reviewer conflict, or
repeated identical finding prevents a defensible fix. Never convert a blocker
into `READY_TO_MERGE` to end the loop.

Declare success only when all conditions hold for one unchanged snapshot:

1. All four fresh reviewers returned `VERDICT: READY_TO_MERGE` with no findings.
2. All applicable deterministic tests, linters, type checks, builds, and
   repository-required gates pass on that snapshot.
3. The worktree has not changed since the reviewers inspected it.
4. No validated finding remains unresolved.

Report the base and snapshot, rounds completed, fixes made, tests run, and the
four final verdicts. Say `READY_TO_MERGE` as a review result only; explicitly
state that no merge was performed.
