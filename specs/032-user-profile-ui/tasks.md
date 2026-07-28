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

**Post-PR review findings**

- Give the Radix `dialog` an accessible name through the profile heading.
- Treat whitespace-only Auth0 name, email, and picture values as missing so
  they cannot produce blank labels/initials or a broken image source.
- Add regression coverage for both findings.

Commit: `fix(ui): address personal profile review findings (T03)`

---

## T04 — Address follow-up PR review

**Tests first**

- An unresolved role state shows an unknown/loading account type, never
  `Member`.
- The dialog and heading share a generated id, without a nested named region.
- Initials are visual-only because the adjacent display name already supplies
  the accessible text.
- A failed profile image request falls back to initials.
- The image request uses `referrerPolicy="no-referrer"`.
- `UserProfile` has direct prop-matrix coverage, including picture without
  name.
- The trigger no longer has a redundant native `title`.

**Implementation**

- Pass `isRolesLoading` into the profile and render a neutral unresolved value.
- Replace the initials `aria-label` with `aria-hidden`.
- Track the failed picture URL and fall back to initials on `onError`.
- Generate the profile heading id with `useId()` in the header and pass it to
  `UserProfile`.
- Replace the nested named `section` with a regular `div`.
- Remove the trigger's `title`.

Commit: `fix(ui): address follow-up profile review (T04)`
