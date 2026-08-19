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
