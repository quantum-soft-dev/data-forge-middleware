/**
 * The location a login redirect must come back to.
 *
 * `Auth0Provider.onRedirectCallback` restores the address bar from
 * `appState.returnTo` with `history.replaceState`, so that string is the whole
 * record of where the user was — anything it omits is gone by the time the app
 * renders. It used to be `window.location.pathname`, which drops the query
 * string, and the Auth0 client caches in memory: every full page load of a
 * protected route starts unauthenticated and takes this round trip, silently
 * when an SSO session is live. `/device-verify?code=XXXX-XXXX` — the URL the
 * PostgreSQL Data Extractor prints for the operator — therefore arrived with an
 * empty code field (issue #211).
 *
 * Same-origin by construction: it is assembled from the current location and
 * is always a root-relative path, never an absolute URL.
 */
export function currentReturnTo(): string {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}`;
}

/**
 * Whether the current location is an Auth0 redirect callback.
 *
 * The same test `auth0-spa-js` applies (`hasAuthParams`): a `state` alongside
 * either a `code` or an `error`. The `state` is what makes it safe — the device
 * verification page carries its own `?code=`, and that one has no `state`.
 */
function isAuthCallbackLocation(): boolean {
  const params = new URLSearchParams(window.location.search);
  return params.has('state') && (params.has('code') || params.has('error'));
}

/**
 * Where to come back to after retrying a login that has already failed once.
 *
 * The authentication-error screen renders at the `redirect_uri` with Auth0's
 * callback parameters still in the URL — the SDK does not clean them up on the
 * failure path — so returning to "the current location" would write a consumed
 * `code`/`state` back into the address bar, which the SDK then reads as a fresh
 * callback. The home page is the honest answer in that case; anywhere else the
 * user really is where they think they are.
 */
export function retryReturnTo(): string {
  return isAuthCallbackLocation() ? '/' : currentReturnTo();
}
