# Tasks — 032 Personal profile view

WIP = 1. Work strictly in order. Each implementation task is test-first
(red → green → one atomic commit).

Frontend gate before every code commit:
`cd frontend && npx tsc --noEmit && npm test`.

Repository gate before every commit:
`./gradlew test -PexcludeIntegration`.

---

## T01 — Specify and document the profile interaction

- Capture the user story, requirements, selected popover design, security
  boundary, file impact, validation, and rollout in `specs/032-*` and
  `docs/cr-user-profile-ui.md`.
- Link the work to GitHub issue #73.

Commit: `docs(ui): plan personal profile view (T01)`

---

## T02 — Add the personal profile popover

**Tests first**

- Avatar is an accessible profile button and opens the profile.
- Populated name, email, profile picture, and Member label render.
- Administrator role renders the Administrator label.
- Missing name, email, and picture render explicit text/initial fallbacks.
- Auth0 subject/raw claims never render.
- Existing logout action and role-based navigation remain present.

Run the focused test and capture the expected red result before implementation.

**Implementation**

- Refactor `UserProfile` into a presentational component that only accepts the
  safe `Auth0User` fields plus an `isAdmin` boolean.
- Remove Auth0 subject and raw role-claim rendering.
- Convert the header initials avatar into a `PopoverTrigger` button.
- Render `UserProfile` inside right-aligned shared `PopoverContent`.
- Preserve the standalone `LogoutButton`.

Commit: `feat(ui): add personal profile popover (T02)`

---

## T03 — Validate and prepare the pull request

- Run frontend type-check and the full frontend suite.
- Run `./gradlew test -PexcludeIntegration`.
- Run the before-PR `./gradlew integrationTest` gate.
- Push the branch and open a draft PR into `develop` with `Closes #73`.
- Review the created PR for correctness, security, accessibility, regressions,
  and test adequacy; fix any findings before handoff.

No source commit is required when validation and review produce no changes.
