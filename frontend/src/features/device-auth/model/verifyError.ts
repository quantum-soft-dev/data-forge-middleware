/**
 * What the verification page tells the user when the code lookup fails.
 *
 * Since #211 the lookup opts out of the global error toast, because the page
 * renders its own message and two reports of one failure is one too many. That
 * makes this string the **only** report, so it may no longer describe every
 * failure as a bad code: before, a network outage or a 500 was at least named
 * accurately by the interceptor's toast standing beside it.
 */

import { getServerErrorMessage, getServerErrorStatus } from '@/shared/api/error-handler';

/**
 * The OAuth-shaped `error_description` the device endpoints return, falling
 * back to the `message` of the application's own error body.
 */
function serverWording(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) {
    return undefined;
  }
  const data = (error as { response?: { data?: { error_description?: unknown } } }).response?.data;
  const description = data?.error_description;
  if (typeof description === 'string' && description.length > 0) {
    return description;
  }
  return getServerErrorMessage(error);
}

export function describeVerifyFailure(error: unknown): string {
  const status = getServerErrorStatus(error);

  // No response at all: the request never reached Data Forge.
  if (status === undefined) {
    return 'Could not reach Data Forge. Check your connection and try again.';
  }

  switch (status) {
    case 400:
      // The authorization was already approved or denied — a definite answer
      // about this code, so the server's wording adds nothing.
      return 'This authorization code has already been processed. Please start a new authorization from your device.';
    case 403:
      return 'You do not have permission to authorize this device.';
    case 404:
      // Expiry is 410 since #219, so a 404 really is an unknown code: telling
      // the user it may have expired would send them to start a new
      // authorization instead of re-reading the code on the device.
      return serverWording(error) ?? 'Device code not found. Please check the code and try again.';
    case 410:
      // The expired-code recovery card carries its own copy; this is the
      // fallback for any caller that renders the message instead.
      return 'This device code has expired. Please start a new authorization request on your device.';
    default:
      // Anything else is about Data Forge, not about the code the user typed:
      // sending them back to retype a good code would be a false accusation.
      return serverWording(error) ?? 'Could not verify the code right now. Please try again in a moment.';
  }
}
