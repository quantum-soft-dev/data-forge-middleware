/**
 * Mock implementation of @auth0/auth0-react for testing.
 *
 * Provides test-safe mock of Auth0 React SDK without requiring Auth0 connection.
 *
 * Mock behaviors:
 * - isAuthenticated: true by default
 * - user: Mock user with admin@example.com
 * - getAccessTokenSilently: Returns 'mock-token'
 * - loginWithRedirect: Jest mock function
 * - logout: Jest mock function
 *
 * Usage in tests:
 * ```typescript
 * import { mockAuth0 } from '../mocks/mockAuth0';
 *
 * vi.mock('@auth0/auth0-react', () => mockAuth0);
 * ```
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see {@link https://auth0.github.io/auth0-react/interfaces/Auth0ContextInterface.html}
 */

import type { User } from '@auth0/auth0-react';

/**
 * Mock Auth0 user with default test data.
 */
export const mockAuth0User: User = {
  sub: 'auth0|test123',
  email: 'admin@example.com',
  email_verified: true,
  name: 'Test Admin',
  nickname: 'admin',
  picture: 'https://s.gravatar.com/avatar/test.png',
  updated_at: new Date().toISOString(),
  // Custom claims for Auth0
  'https://api.dataforge.com/roles': ['ROLE_ADMIN'],
  'https://api.dataforge.com/accountId': 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
};

/**
 * Mock implementation of useAuth0 hook.
 *
 * Returns default authenticated state with ROLE_ADMIN.
 * Can be customized in individual tests by mocking specific return values.
 */
export const mockUseAuth0 = () => ({
  // Authentication state
  isAuthenticated: true,
  isLoading: false,
  error: undefined,

  // User data
  user: mockAuth0User,

  // Token management
  getAccessTokenSilently: vi.fn().mockResolvedValue('mock-token'),
  getAccessTokenWithPopup: vi.fn().mockResolvedValue('mock-token'),
  getIdTokenClaims: vi.fn().mockResolvedValue({
    __raw: 'mock-id-token',
    name: 'Test Admin',
    email: 'admin@example.com',
  }),

  // Authentication actions
  loginWithRedirect: vi.fn().mockResolvedValue(undefined),
  loginWithPopup: vi.fn().mockResolvedValue(undefined),
  logout: vi.fn(),

  // SDK metadata
  buildAuthorizeUrl: vi.fn().mockResolvedValue('https://test.auth0.com/authorize'),
  buildLogoutUrl: vi.fn().mockReturnValue('https://test.auth0.com/logout'),
  handleRedirectCallback: vi.fn().mockResolvedValue({ appState: {} }),
});

/**
 * Mock Auth0Provider component for testing.
 *
 * Simply renders children without Auth0 SDK initialization.
 */
export const MockAuth0Provider = ({ children }: { children: any }) => {
  return children;
};

/**
 * Mock withAuthenticationRequired HOC for testing.
 *
 * Returns the component without authentication check.
 */
export const mockWithAuthenticationRequired = (component: any) => {
  return component;
};

/**
 * Export mock module for vi.mock() usage.
 */
export const mockAuth0 = {
  useAuth0: mockUseAuth0,
  Auth0Provider: MockAuth0Provider,
  withAuthenticationRequired: mockWithAuthenticationRequired,
};

/**
 * Helper function to create custom mock user with different roles.
 *
 * @param roles - Array of roles (e.g., ['ROLE_USER'])
 * @param overrides - Custom user properties
 * @returns Mock Auth0 user
 */
export const createMockAuth0User = (
  roles: string[] = ['ROLE_ADMIN'],
  overrides: Partial<User> = {}
): User => {
  return {
    ...mockAuth0User,
    ...overrides,
    'https://api.dataforge.com/roles': roles,
  };
};

/**
 * Helper function to create custom useAuth0 mock with specific state.
 *
 * @param overrides - Custom hook return values
 * @returns Mock useAuth0 hook
 */
export const createMockUseAuth0 = (overrides: Partial<ReturnType<typeof mockUseAuth0>> = {}) => {
  return {
    ...mockUseAuth0(),
    ...overrides,
  };
};

/**
 * Mock unauthenticated state for testing login flows.
 */
export const mockUseAuth0Unauthenticated = () => ({
  ...mockUseAuth0(),
  isAuthenticated: false,
  user: undefined,
});

/**
 * Mock loading state for testing loading spinners.
 */
export const mockUseAuth0Loading = () => ({
  ...mockUseAuth0(),
  isLoading: true,
  isAuthenticated: false,
  user: undefined,
});

/**
 * Mock error state for testing error handling.
 */
export const mockUseAuth0Error = (errorMessage: string = 'Authentication failed') => ({
  ...mockUseAuth0(),
  isAuthenticated: false,
  error: new Error(errorMessage),
  user: undefined,
});
