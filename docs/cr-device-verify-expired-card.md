# CR — Reachable expired-code recovery on device verification

**Issue:** [#219](https://github.com/quantum-soft-dev/data-forge-middleware/issues/219)
**Status:** implemented

## Summary

An expired device authorization now reaches the existing **Code Expired** recovery card instead
of sharing the unknown-code error. The unused DELETE-denial client export is removed.

## Implementation

`GET /api/v1/device/verify?code=` returns `410 Gone` when the service reports an expired
authorization; it continues to return `404 Not Found` only when no authorization exists. The
verification page maps `410` to its `expired` state, instructing the operator to start a new
authorization request on the device. Its existing generic error flow remains responsible for all
other statuses.

`POST /api/v1/device/verify` (approve) answers `410` for the same reason, because it is the second
and likelier way into the expired state: the confirm card does not poll — `useVerifyInfo` has no
`refetchInterval` — so a code that was valid when the page loaded can run out while the card sits
open, and only the approval discovers it. Without this the operator was told "Failed to authorize
device. Please try again.", a retry that can never succeed. Both action handlers route a `410` to
the same recovery card. The `DELETE` denial route is untouched: `DeviceAuthorizationService.deny`
does not test expiry at all, so it cannot raise the condition.

The 404 wording drops "or expired" for the same split: with expiry answered by 410, a 404 really is
an unknown code, and the toast for this lookup is suppressed (#211), so that string is the only
report the operator gets.

## File Changes

- `DeviceAuthorizationController.java` — returns and documents the distinct expired response on
  both the lookup and the approve action; the `410` annotations no longer promise a response body
  the handlers do not send, and the `DELETE` denial no longer documents an expiry it cannot raise.
- `DeviceVerifyPage.tsx` — maps `410 Gone` to the reachable recovery card, from the lookup and from
  either action.
- `verifyError.ts` — 404 stops naming expiry; 410 gains wording of its own.
- `deviceAuthApi.ts` — removes the uncalled DELETE-denial export and updates status documentation.
- `DeviceVerifyPage.test.tsx` and `DeviceAuthorizationControllerTest.java` — pin the UI and API
  status behavior.
- `device-flow-client-guide.md` — describes the recovery route for expired browser verification.

## Compatibility

No schema migration, gRPC/protobuf, configuration, route, query-key, or request/response DTO
change is required. Existing clients that treat non-2xx lookup responses uniformly remain valid;
the browser gains the additional `410` branch.
