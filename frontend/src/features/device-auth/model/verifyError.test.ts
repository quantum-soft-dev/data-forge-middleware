/**
 * What the verification page tells the user when the lookup fails.
 *
 * Since #211 the lookup opts out of the global error toast, so this string is
 * the *only* report of a failure — which means it can no longer describe every
 * failure as a bad code: a network outage or a 500 used to be named accurately
 * by the interceptor's own toast beside it.
 */

import { describe, it, expect } from 'vitest';
import { describeVerifyFailure } from './verifyError';

function httpError(status: number, data: Record<string, unknown> = {}) {
  return { response: { status, data } };
}

describe('describeVerifyFailure', () => {
  it('names an already-processed authorization (400)', () => {
    expect(describeVerifyFailure(httpError(400))).toMatch(/already been processed/i);
  });

  it('names an unknown or expired code (404), preferring the server wording', () => {
    expect(describeVerifyFailure(httpError(404, { error_description: 'Device code not found' })))
      .toBe('Device code not found');
    expect(describeVerifyFailure(httpError(404))).toMatch(/not found or expired/i);
  });

  it('does not blame the code for a network failure', () => {
    const message = describeVerifyFailure(new Error('Network Error'));

    expect(message).not.toMatch(/not found or expired/i);
    expect(message).toMatch(/connection/i);
  });

  it('does not blame the code for a server failure', () => {
    const message = describeVerifyFailure(httpError(500));

    expect(message).not.toMatch(/not found or expired/i);
    expect(message).toMatch(/try again/i);
  });

  it('does not blame the code for a refused request', () => {
    const message = describeVerifyFailure(httpError(403));

    expect(message).not.toMatch(/not found or expired/i);
    expect(message).toMatch(/permission/i);
  });

  it('quotes the server for a failure it has no wording of its own for', () => {
    expect(describeVerifyFailure(httpError(503, { message: 'Upstream unavailable' })))
      .toBe('Upstream unavailable');
  });
});
