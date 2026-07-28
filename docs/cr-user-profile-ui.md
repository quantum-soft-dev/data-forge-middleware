# CR: Personal Profile View (032)

**Status**: Planned
**GitHub issue**: [#73](https://github.com/quantum-soft-dev/data-forge-middleware/issues/73)
**Spec**: `specs/032-user-profile-ui/`
**Scope**: Protected frontend header

## Motivation

Signed-in users can currently see only their initials in the top-right corner.
The control does not reveal which name or email address is active, which is
especially confusing for people who use more than one account.

## User experience

The initials avatar becomes a button. Activating it opens a compact, right-aligned
profile popover containing:

- the Auth0 profile image, or initials when no image is available;
- display name, with an explicit missing-value fallback;
- email address, with an explicit missing-value fallback;
- the friendly account type, Administrator or Member.

The existing sign-out icon remains next to the profile trigger. The popover uses
the shared Radix primitive, so keyboard activation, focus management, Escape,
and outside-click dismissal follow the same behavior as other application
overlays.

## Data and security

The view consumes the existing `useAuth` session state and makes no new network
request. Only `name`, `email`, and `picture` are treated as personal display
fields. The account-type label is derived from the role boolean that the header
already uses for navigation.

Auth0 subjects, access/ID tokens, custom claims, and other authentication
identifiers are intentionally excluded. The old unused profile component showed
`user.sub`; this implementation removes that behavior.

## Architecture

`features/auth/ui/UserProfile.tsx` becomes a presentational component.
`widgets/header/Header.tsx` owns the interaction and supplies the typed user and
resolved role. `shared/ui/ui/popover.tsx` provides the accessible overlay.

No route, backend endpoint, persistence, or migration is added.

## Validation

Automated tests cover populated and missing profile data, account type,
sensitive-data exclusion, opening the profile from the avatar, and logout/nav
regression behavior. The frontend TypeScript/test gate and repository backend
gates must pass before the pull request is opened.
