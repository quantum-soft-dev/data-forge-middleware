# CR: Consolidate /api/v1/history/batches into a single controller

**Date:** 2026-07-23
**Status:** Implemented
**Related:** PR #57 (`fix/rse-passthrough`), GKE test incident 2026-07-23 (cross-account 403 surfaced as 500)

## Problem

Two controllers were mapped to the same routes under `ApiRoutes.HISTORY_BATCHES`
(`/api/v1/history/batches`):

- `BatchHistoryController` — `@GetMapping` without `produces`; caught
  `UnauthorizedBatchAccessException` / `BatchNotFoundException` itself and returned ad-hoc
  error bodies.
- `BatchHistoryAdminController` — same routes with `produces = application/json`; resolved
  accountId via a three-step JWT strategy and rethrew domain exceptions as
  `ResponseStatusException`.

Because the mappings differed only in `produces`, **routing depended on the request's Accept
header**: requests without `Accept` were served by `BatchHistoryController`, requests with
`Accept: application/json` (all real frontend clients) by `BatchHistoryAdminController`. The
two code paths had diverging error handling, which caused a prod-visible bug: contract test
TC06 exercised one controller while GKE traffic hit the other, so a cross-account 403 leaked
out as a 500 (patched separately by the `ResponseStatusException` passthrough handler, PR #57).

## Decision

Keep **`BatchHistoryController`** as the single controller for these routes (it owns all six
endpoints: list, details, file download, ZIP, Excel export, batch errors) and delete
`BatchHistoryAdminController` entirely.

- **Account resolution:** `AuthorizationHelper.getAuthenticatedAccountId()` — the shared
  component whose strategy is a superset of the admin controller's three-step approach
  (namespaced `accountId` claim → legacy `accountId` claim → `account_id` claim → `sub` as
  UUID → email / `preferred_username` database lookup).
- **Error handling:** the list and details endpoints no longer catch domain exceptions.
  `BatchNotFoundException` (404), `UnauthorizedBatchAccessException` (403) and
  `AuthorizationHelper.UnauthorizedException` (401) propagate to `GlobalExceptionHandler`,
  which renders the standard `ErrorResponseDto` (`timestamp`/`status`/`error`/`message`/`path`).
  Streaming endpoints (ZIP, Excel) keep their local handling — they write to the committed
  response directly.
- **Preserved production (JSON-path) behavior:**
  - list returns **200 with an empty page** for authenticated users without a linked Account
    (pure Auth0 admins) via `getOptionalAuthenticatedAccountId()`;
  - details returns **401** for such users;
  - `limit` is passed through to `BatchHistoryService`, which defaults invalid/absent values
    to 20;
  - Micrometer timers `batch.history.user.list` and `batch.details.user.load` are kept.
- **Behavior changes vs the old no-Accept path** (which no real client used):
  - unlinked-user list: 403 → 200 empty page; unlinked-user details: 403 → 401;
  - invalid cursor: 500 → 400 (`IllegalArgumentException` handled globally);
  - 403/404 bodies are the standard `ErrorResponseDto` instead of ad-hoc maps.

## Tests

`BatchHistoryContractTest` covers the surviving behavior both **with and without an Accept
header**: TC01/TC01b (list), TC05/TC05b (details 200), TC06/TC06b (cross-account 403),
TC07/TC07b (404), TC14/TC14b (unlinked user → empty list), TC15/TC15b (unlinked user details
→ 401). `TestSecurityConfig` gained the `mock-jwt-token-no-account` mock token (authenticated
user with no accountId claims, non-UUID subject, and an email absent from test data).
