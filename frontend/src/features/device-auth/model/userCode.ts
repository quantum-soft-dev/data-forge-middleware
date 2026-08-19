/**
 * The device user code shape (RFC 8628 `user_code`).
 *
 * The server mints eight characters and renders them as `XXXX-XXXX`
 * (DeviceAuthorizationService#generateUserCode), so the formatted value the
 * user sees and types is nine characters long. That one-character difference
 * is the whole of issue #211: gating the lookup on eight characters looked up
 * a seven-character code on the keystroke before last, the backend answered
 * 404, and the global interceptor toasted "Resource not found." over a flow
 * that then succeeded.
 *
 * Single home for the shape, so the page, its submit button and the query hook
 * cannot disagree about when a code is complete.
 */

/** Characters in a code, excluding the group separator. */
export const USER_CODE_CHARS = 8;

/** Length of the formatted code, `XXXX-XXXX`. */
export const USER_CODE_LENGTH = USER_CODE_CHARS + 1;

/**
 * Normalize free input into the `XXXX-XXXX` presentation: upper case,
 * non-alphanumeric characters dropped, a separator after the fourth character,
 * and anything past the eighth ignored.
 *
 * Takes `unknown` on purpose. One caller is a text input, but the other is the
 * `?code=` search parameter, and TanStack Router parses search values with
 * `JSON.parse`: the alphabet allows the digits 2-9, so an all-digit code with
 * its separator removed arrives as a **number**, and a repeated parameter
 * arrives as an **array**. Neither has `toUpperCase`, and this runs during
 * render, so the throw would take the page to the error boundary rather than
 * degrading to "enter your code".
 */
export function formatUserCode(code: unknown): string {
  const cleaned = String(code ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (cleaned.length <= 4) {
    return cleaned;
  }
  return `${cleaned.slice(0, 4)}-${cleaned.slice(4, USER_CODE_CHARS)}`;
}

/**
 * Whether a formatted code carries all eight characters — the only state in
 * which asking the server about it is a question with a meaningful answer.
 */
export function isCompleteUserCode(code: unknown): boolean {
  return formatUserCode(code).length === USER_CODE_LENGTH;
}
