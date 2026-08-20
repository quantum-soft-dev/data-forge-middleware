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

## File Changes

- `DeviceAuthorizationController.java` — returns and documents the distinct expired response.
- `DeviceVerifyPage.tsx` — maps `410 Gone` to the reachable recovery card.
- `deviceAuthApi.ts` — removes the uncalled DELETE-denial export and updates status documentation.
- `DeviceVerifyPage.test.tsx` and `DeviceAuthorizationControllerTest.java` — pin the UI and API
  status behavior.
- `device-flow-client-guide.md` — describes the recovery route for expired browser verification.

## Compatibility

No schema migration, gRPC/protobuf, configuration, route, query-key, or request/response DTO
change is required. Existing clients that treat non-2xx lookup responses uniformly remain valid;
the browser gains the additional `410` branch.
