# 032 — Personal profile view

**GitHub issue**: [#73](https://github.com/quantum-soft-dev/data-forge-middleware/issues/73)

## Problem

The protected application header represents the signed-in user with a two-letter
avatar, but the avatar is not interactive and there is no UI where users can
inspect the personal data attached to their session.

An older, unused `UserProfile` component exists, but it is not suitable as-is:
it reads Auth0 independently from the header, uses pre-024 visual styles, and
renders the Auth0 subject identifier.

## User story

As a signed-in administrator or regular user, I want to open my profile from the
header so that I can confirm which personal account is active.

## Functional requirements

- **FR-001** The initials avatar in the protected header MUST be an interactive
  button.
- **FR-002** Activating the avatar MUST open a profile popover.
- **FR-003** The popover MUST show the user's profile image when available and
  MUST fall back to initials when it is not.
- **FR-004** The popover MUST show the Auth0 display name and email when
  available.
- **FR-005** Missing name or email values MUST render explicit, readable
  fallbacks.
- **FR-006** The popover MUST identify the account type as Administrator or
  Member using the already-resolved header role.
- **FR-007** The profile MUST NOT show the Auth0 subject, token values, raw
  claims, or other authentication identifiers.
- **FR-008** The existing logout action and role-based navigation MUST remain
  unchanged.
- **FR-009** The trigger and popover MUST support keyboard activation, focus
  management, Escape dismissal, and accessible labels through the shared Radix
  primitive.
- **FR-010** The UI MUST use the monitoring visual language and shared tokens.

## Success criteria

- **SC-001** A user can open the profile by clicking or keyboard-activating the
  avatar.
- **SC-002** Name, email, image/initials fallback, and account type render from
  the existing session data without an additional API request.
- **SC-003** Missing personal fields never produce an empty heading, empty data
  row, or broken image.
- **SC-004** Automated tests cover opening the popover, populated data, missing
  data fallbacks, administrator/member labeling, sensitive-data exclusion, and
  logout presence.
- **SC-005** TypeScript compilation and the full frontend test suite pass.

## Out of scope

- Editing personal data.
- Uploading an avatar.
- A new profile route or backend endpoint.
- Displaying raw Auth0 claims, identifiers, or tokens.
- Changing logout behavior.
