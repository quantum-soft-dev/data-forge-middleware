# Tasks: Token Refresh and Auto-Logout

**Input**: Design documents from `/specs/012-key-caching-logic/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Included per Constitution Principle XI (TDD NON-NEGOTIABLE)

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions
- **Frontend**: `frontend/src/`, `frontend/src/__tests__/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project structure and type definitions

- [x] T001 [P] Create type definitions in `frontend/src/shared/api/types.ts` with `GetAccessTokenFn`, `LogoutFn`, `Auth0ExpiredErrorCode`, `SessionExpiryReason`, `SessionExpiryData`, `RetryableAxiosConfig`
- [x] T002 [P] Create `frontend/src/shared/lib/auth/` directory for auth utilities

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST complete before ANY user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T003 Create token refresh manager skeleton in `frontend/src/shared/api/token-refresh.ts` with `initTokenRefresh()`, `refreshTokenWithLock()` function signatures (no implementation yet)
- [x] T004 Create session expiry module skeleton in `frontend/src/shared/lib/auth/session-expiry.ts` with `setSessionExpired()`, `getSessionExpired()`, `clearSessionExpired()` function signatures

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Automatic Token Refresh on API Error (Priority: P1) 🎯 MVP

**Goal**: When access token expires during API call, automatically refresh and retry request without user intervention

**Independent Test**: Make API call with expired token → verify refresh happens → verify original request completes successfully

### Tests for User Story 1 ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T005 [P] [US1] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test `refreshTokenWithLock()` returns new token on success
- [x] T006 [P] [US1] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test lock prevents multiple concurrent refreshes (second call waits for first)
- [x] T007 [P] [US1] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test `_retry` flag prevents infinite loops
- [x] T008 [P] [US1] Unit test: `frontend/src/shared/api/__tests__/interceptors.test.ts` - test 401 response triggers refresh and retry
- [x] T009 [P] [US1] Unit test: `frontend/src/shared/api/__tests__/interceptors.test.ts` - test successful retry uses new token in Authorization header

### Implementation for User Story 1

- [x] T010 [US1] Implement `refreshTokenWithLock()` in `frontend/src/shared/api/token-refresh.ts` - Promise-based lock, calls `getAccessTokenSilently({ cacheMode: 'off' })`
- [x] T011 [US1] Implement `initTokenRefresh()` in `frontend/src/shared/api/token-refresh.ts` - stores Auth0 callbacks (`getAccessTokenSilently`, `logout`)
- [x] T012 [US1] Modify `frontend/src/shared/api/interceptors.ts` - add response interceptor that catches 401, calls `refreshTokenWithLock()`, retries with `instance(error.config)`
- [x] T013 [US1] Add `_retry` flag handling in interceptor to prevent infinite retry loops
- [x] T014 [US1] Wire up `initTokenRefresh()` in `frontend/src/app/App.tsx` after Auth0 is ready

**Checkpoint**: Token refresh working - 401 errors trigger refresh and retry automatically

---

## Phase 4: User Story 2 - Auto-Logout on Refresh Token Expiry (Priority: P1)

**Goal**: When refresh token is expired, gracefully logout user and redirect to login page

**Independent Test**: Simulate expired refresh token → verify logout is called → verify redirect to login

### Tests for User Story 2 ⚠️

- [x] T015 [P] [US2] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test `invalid_grant` error triggers logout callback
- [x] T016 [P] [US2] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test `missing_refresh_token` error triggers logout callback
- [x] T017 [P] [US2] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test `login_required` error triggers logout callback
- [x] T018 [P] [US2] Unit test: `frontend/src/shared/lib/auth/__tests__/session-expiry.test.ts` - test `setSessionExpired()` writes to sessionStorage

### Implementation for User Story 2

- [x] T019 [US2] Implement error handling in `refreshTokenWithLock()` - catch Auth0 errors (`invalid_grant`, `missing_refresh_token`, `login_required`)
- [x] T020 [US2] Call `setSessionExpired()` before logout when refresh fails due to expired token
- [x] T021 [US2] Call `logout()` callback after setting session expired flag
- [x] T022 [US2] Update `frontend/src/shared/api/error-handler.ts` - remove redundant 401 toast (now handled by refresh interceptor)

**Checkpoint**: Auto-logout working - expired refresh token triggers clean logout flow

---

## Phase 5: User Story 3 - Session Expiry Notification (Priority: P2)

**Goal**: Display clear message on login page explaining session expired due to inactivity

**Independent Test**: Force session expiry → verify message appears on login page → verify message clears after login

### Tests for User Story 3 ⚠️

- [x] T023 [P] [US3] Unit test: `frontend/src/shared/lib/auth/__tests__/session-expiry.test.ts` - test `getSessionExpired()` reads from sessionStorage
- [x] T024 [P] [US3] Unit test: `frontend/src/shared/lib/auth/__tests__/session-expiry.test.ts` - test `clearSessionExpired()` removes from sessionStorage
- [x] T025 [P] [US3] Unit test: `frontend/src/shared/lib/auth/__tests__/session-expiry.test.ts` - test graceful handling when sessionStorage unavailable
- [x] T026 [P] [US3] Component test: `frontend/src/entities/user-session/ui/__tests__/SessionExpiredBanner.test.tsx` - test renders message when expired
- [x] T027 [P] [US3] Component test: `frontend/src/entities/user-session/ui/__tests__/SessionExpiredBanner.test.tsx` - test does not render when not expired
- [x] T028 [P] [US3] Component test: `frontend/src/entities/user-session/ui/__tests__/SessionExpiredBanner.test.tsx` - test clears state after displaying

### Implementation for User Story 3

- [x] T029 [US3] Implement `setSessionExpired()` in `frontend/src/shared/lib/auth/session-expiry.ts` - write JSON to sessionStorage with key `dfm_session_expired`
- [x] T030 [US3] Implement `getSessionExpired()` in `frontend/src/shared/lib/auth/session-expiry.ts` - read and parse from sessionStorage
- [x] T031 [US3] Implement `clearSessionExpired()` in `frontend/src/shared/lib/auth/session-expiry.ts` - remove from sessionStorage
- [x] T032 [US3] Create `SessionExpiredBanner` component in `frontend/src/entities/user-session/ui/SessionExpiredBanner.tsx` - displays alert with message "Your session has expired due to inactivity. Please log in again."
- [x] T033 [US3] Add ARIA attributes for accessibility in `SessionExpiredBanner` (role="alert", aria-live="polite")
- [x] T034 [US3] Integrate `SessionExpiredBanner` into login page or Auth0Provider redirect callback

**Checkpoint**: Session expiry notification working - users see clear message after forced logout

---

## Phase 6: User Story 4 - Prevent Request Retry Storm (Priority: P2)

**Goal**: When multiple API requests fail with 401 simultaneously, only one token refresh occurs

**Independent Test**: Trigger 5 concurrent API calls with expired token → verify only 1 refresh request → verify all 5 requests retry successfully

### Tests for User Story 4 ⚠️

- [x] T035 [P] [US4] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test 5 concurrent calls to `refreshTokenWithLock()` result in single refresh
- [x] T036 [P] [US4] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test all concurrent callers receive same token
- [x] T037 [P] [US4] Unit test: `frontend/src/shared/api/__tests__/token-refresh.test.ts` - test lock resets after refresh completes (next 401 triggers new refresh)
- [x] T038 [P] [US4] Integration test: `frontend/src/shared/api/__tests__/token-refresh.integration.test.ts` - test end-to-end flow with mocked Auth0 and multiple concurrent requests

### Implementation for User Story 4

- [x] T039 [US4] Refine `refreshTokenWithLock()` - ensure `refreshPromise` is shared across all concurrent callers
- [x] T040 [US4] Add proper cleanup in finally block - reset `isRefreshing` and `refreshPromise` only after all waiters resolved
- [x] T041 [US4] Test with real concurrent requests using Promise.all() in development

**Checkpoint**: Concurrent request handling working - no duplicate refresh attempts

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Edge cases, error handling, and documentation

- [x] T042 [P] Handle network error during refresh - show toast "Network error. Please check your connection." without logout
- [x] T043 [P] Handle Auth0 service unavailable - show toast "Authentication service unavailable. Please try again later."
- [x] T044 [P] Add debug logging to token refresh flow (use existing `logger` from `frontend/src/shared/lib/logger`)
- [x] T045 [P] Update `frontend/src/shared/api/error-handler.ts` - ensure non-401 errors still show appropriate toasts
- [ ] T046 Run manual verification per quickstart.md scenarios
- [ ] T047 Update any affected test mocks in existing test files

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational - Core MVP
- **User Story 2 (Phase 4)**: Depends on User Story 1 (needs refresh mechanism to fail)
- **User Story 3 (Phase 5)**: Depends on User Story 2 (needs logout flow)
- **User Story 4 (Phase 6)**: Depends on User Story 1 (needs basic refresh working)
- **Polish (Phase 7)**: Depends on all user stories

### User Story Dependencies

```
         ┌────────────────┐
         │  Foundational  │
         │   (Phase 2)    │
         └───────┬────────┘
                 │
                 ▼
         ┌────────────────┐
         │  User Story 1  │ ◄── MVP: Token Refresh
         │    (P1)        │
         └───────┬────────┘
                 │
        ┌────────┴────────┐
        │                 │
        ▼                 ▼
┌───────────────┐  ┌───────────────┐
│ User Story 2  │  │ User Story 4  │
│   (P1)        │  │    (P2)       │
│  Auto-Logout  │  │ Concurrent    │
└───────┬───────┘  └───────────────┘
        │
        ▼
┌───────────────┐
│ User Story 3  │
│    (P2)       │
│  Notification │
└───────────────┘
```

### Parallel Opportunities

**Phase 1 (Setup)**:
```bash
# Both setup tasks can run in parallel:
T001: Create type definitions
T002: Create auth directory
```

**Phase 3 (User Story 1 Tests)**:
```bash
# All US1 tests can run in parallel:
T005, T006, T007, T008, T009
```

**Phase 5 (User Story 3 Tests)**:
```bash
# All US3 tests can run in parallel:
T023, T024, T025, T026, T027, T028
```

**Phase 6 (User Story 4 Tests)**:
```bash
# All US4 tests can run in parallel:
T035, T036, T037, T038
```

---

## Implementation Strategy

### MVP First (User Story 1 + 2 Only)

1. Complete Phase 1: Setup (T001-T002)
2. Complete Phase 2: Foundational (T003-T004)
3. Complete Phase 3: User Story 1 - Token Refresh (T005-T014)
4. Complete Phase 4: User Story 2 - Auto-Logout (T015-T022)
5. **STOP and VALIDATE**: Test token refresh + logout independently
6. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → Token refresh works → **MVP!**
3. Add User Story 2 → Auto-logout on expiry → **Full P1 functionality**
4. Add User Story 3 → Session expiry message → Better UX
5. Add User Story 4 → Concurrent handling → Robust solution
6. Polish → Production ready

---

## Summary

| Phase | Tasks | User Story | Priority |
|-------|-------|------------|----------|
| 1. Setup | T001-T002 | - | - |
| 2. Foundational | T003-T004 | - | - |
| 3. US1 | T005-T014 | Token Refresh | P1 |
| 4. US2 | T015-T022 | Auto-Logout | P1 |
| 5. US3 | T023-T034 | Session Notification | P2 |
| 6. US4 | T035-T041 | Concurrent Handling | P2 |
| 7. Polish | T042-T047 | - | - |

**Total Tasks**: 47
- Setup: 2
- Foundational: 2
- User Story 1: 10 (5 tests + 5 impl)
- User Story 2: 8 (4 tests + 4 impl)
- User Story 3: 12 (6 tests + 6 impl)
- User Story 4: 7 (4 tests + 3 impl)
- Polish: 6

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Tests MUST fail before implementation (TDD)
- Commit after each task or logical group
- Stop at any checkpoint to validate story
- US1 + US2 together form the MVP (both P1)
