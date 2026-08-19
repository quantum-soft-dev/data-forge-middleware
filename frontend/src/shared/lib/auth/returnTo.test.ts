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
import { currentReturnTo } from './returnTo';

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
