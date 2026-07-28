# Plan — 032 Personal profile view

## Current state

- `widgets/header/Header.tsx` already obtains a typed Auth0 user and resolved
  role state from `entities/user-session/api/useAuth`.
- The avatar is a non-interactive `span`; logout is a separate control.
- `features/auth/ui/UserProfile.tsx` is unused and exposes `user.sub`.
- The shared `Popover` primitive supplies keyboard interaction, focus
  management, outside-click handling, and Escape dismissal.

## Design

Keep profile discovery in the global header and avoid a route for a small,
read-only data set:

1. Refactor `UserProfile` into a presentational component that accepts the
   typed user plus the already-resolved administrator flag.
2. Remove its direct Auth0/environment dependency and all raw claim/subject
   rendering.
3. Wrap the header avatar in the shared Radix `Popover`.
4. Render the refactored profile in right-aligned `PopoverContent`.
5. Preserve `LogoutButton` as the adjacent, existing action.

The profile uses only fields already present in the Auth0 ID-token user object:
`name`, `email`, and `picture`. It performs no network request and stores no new
data.

## File impact

- `frontend/src/features/auth/ui/UserProfile.tsx`
- `frontend/src/widgets/header/Header.tsx`
- `frontend/src/widgets/header/Header.test.tsx`
- `docs/cr-user-profile-ui.md`
- `specs/032-user-profile-ui/*`

No backend, database, API, or routing changes are required.

## Validation

- Focused red/green test:
  `npm --prefix frontend test -- src/widgets/header/Header.test.tsx`
- Frontend task gate:
  `cd frontend && npx tsc --noEmit && npm test`
- Repository backend task gate:
  `./gradlew test -PexcludeIntegration`
- Before-PR gate:
  `./gradlew integrationTest`

## Risks and mitigations

- **Radix/jsdom mismatch**: test through accessible roles and user events, not
  implementation state.
- **Long profile values**: constrain the popover and allow name/email wrapping.
- **Sensitive data leakage**: expose an explicit typed prop subset and assert
  that the Auth0 subject is absent.
- **Role loading**: use the same `isAdmin` result as navigation; unresolved
  roles retain the safe Member label until role resolution completes.
