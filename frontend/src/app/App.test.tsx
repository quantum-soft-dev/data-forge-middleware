/**
 * The authentication-error screen's Try Again button.
 *
 * It is the one login redirect that carried no `appState` at all, so
 * `Auth0Provider.onRedirectCallback` fell through to `window.location.pathname`
 * — which at callback time is the `redirect_uri`, i.e. `/`. An operator whose
 * silent Auth0 check failed on `/device-verify?code=XXXX-XXXX` therefore logged
 * in successfully and landed on the dashboard with the code gone: the exact
 * symptom of #211, on the path the affected user is most likely to hit.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppContent } from './App';

const loginWithRedirect = vi.fn();

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: () => ({
    isLoading: false,
    isAuthenticated: false,
    error: new Error('login_required'),
    getAccessTokenSilently: vi.fn(),
    loginWithRedirect,
    logout: vi.fn(),
  }),
}));

vi.mock('@/app/providers', () => ({
  Auth0Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  QueryProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  RouterProvider: () => <div>router</div>,
}));

vi.mock('@/shared/api/interceptors', () => ({
  setupInterceptors: vi.fn(),
  setupResponseInterceptor: vi.fn(),
}));
vi.mock('@/shared/api/token-refresh', () => ({ initTokenRefresh: vi.fn() }));
vi.mock('@/shared/api/error-handler', () => ({ setupErrorHandler: vi.fn() }));
vi.mock('@/entities/user-session/ui/SessionExpiredBanner', () => ({
  SessionExpiredBanner: () => null,
}));

const initialUrl = window.location.href;

beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState({}, '', '/device-verify?code=M9Q2-4AML');
});

afterEach(() => {
  window.history.replaceState({}, '', initialUrl);
});

describe('AppContent, authentication error', () => {
  it('returns to the whole current URL when the user retries', async () => {
    render(<AppContent />);

    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }));

    expect(loginWithRedirect).toHaveBeenCalledWith({
      appState: { returnTo: '/device-verify?code=M9Q2-4AML' },
    });
  });
});
