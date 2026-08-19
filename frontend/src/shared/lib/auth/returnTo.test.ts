/**
 * Where the login redirect comes back to.
 *
 * The Auth0 SPA client caches in memory, so any full page load of a protected
 * route starts unauthenticated and goes through Auth0 — silently, when an SSO
 * session is live. `onRedirectCallback` then restores the URL from
 * `appState.returnTo`, so whatever that string omits is dropped from the
 * address bar. Omitting the query string broke `/device-verify?code=XXXX-XXXX`,
 * the URL the PostgreSQL Data Extractor prints for the operator: the field came
 * up empty and the code had to be typed by hand (issue #211).
 */

import { describe, it, expect, afterEach } from 'vitest';
import { currentReturnTo, retryReturnTo } from './returnTo';

const initialUrl = window.location.href;

afterEach(() => {
  window.history.replaceState({}, '', initialUrl);
});

describe('currentReturnTo', () => {
  it('keeps the query string', () => {
    window.history.replaceState({}, '', '/device-verify?code=M9Q2-4AML');

    expect(currentReturnTo()).toBe('/device-verify?code=M9Q2-4AML');
  });

  it('keeps the fragment', () => {
    window.history.replaceState({}, '', '/account/sites/s1?tab=delta#checkpoints');

    expect(currentReturnTo()).toBe('/account/sites/s1?tab=delta#checkpoints');
  });

  it('is the bare path when there is nothing else', () => {
    window.history.replaceState({}, '', '/dashboard');

    expect(currentReturnTo()).toBe('/dashboard');
  });
});

describe('retryReturnTo', () => {
  // The authentication-error screen renders at the redirect_uri with Auth0's
  // own callback parameters still in the URL — the SDK does not clean them on
  // the failure path. Restoring that URL after a successful retry would put a
  // consumed `code`/`state` back in the address bar, which the SDK reads as a
  // fresh callback (#211 review round 3).
  it('refuses to return to an Auth0 callback URL', () => {
    window.history.replaceState({}, '', '/?code=CONSUMED&state=OLD');
    expect(retryReturnTo()).toBe('/');

    window.history.replaceState({}, '', '/?error=access_denied&state=OLD');
    expect(retryReturnTo()).toBe('/');
  });

  it('keeps a real location, including one whose own parameter is named code', () => {
    // The device code lives in `?code=` too, but carries no `state`.
    window.history.replaceState({}, '', '/device-verify?code=M9Q2-4AML');
    expect(retryReturnTo()).toBe('/device-verify?code=M9Q2-4AML');

    window.history.replaceState({}, '', '/account/sites/s1?tab=delta');
    expect(retryReturnTo()).toBe('/account/sites/s1?tab=delta');
  });
});
