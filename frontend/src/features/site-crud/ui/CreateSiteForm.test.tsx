/**
 * Unit tests for CreateSiteForm component.
 *
 * Tests:
 * - Form rendering and field validation
 * - Password generation functionality
 * - Password copy functionality
 * - Form submission with valid/invalid data
 * - Success and error handling
 *
 * Feature: 007-adding-a-site (T028, US1)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CreateSiteForm } from './CreateSiteForm';
import * as queries from '../model/queries';

// Mock the queries module
vi.mock('../model/queries', () => ({
  useCreateSite: vi.fn(),
}));

// Mock the password generator
vi.mock('@/shared/lib/password-generator', () => ({
  generatePassword: vi.fn(() => 'Generated1234'),
}));

// Mock toast notifications
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

// Mock clipboard API
const mockWriteText = vi.fn().mockResolvedValue(undefined);
Object.assign(navigator, {
  clipboard: {
    writeText: mockWriteText,
  },
});

describe('CreateSiteForm', () => {
  let queryClient: QueryClient;
  let mockMutateAsync: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    mockMutateAsync = vi.fn();

    // Mock the useCreateSite hook
    vi.mocked(queries.useCreateSite).mockReturnValue({
      mutateAsync: mockMutateAsync,
      isPending: false,
      isError: false,
      isSuccess: false,
      error: null,
      data: undefined,
      mutate: vi.fn(),
      reset: vi.fn(),
      status: 'idle',
      variables: undefined,
      context: undefined,
      failureCount: 0,
      failureReason: null,
      isPaused: false,
      submittedAt: 0,
    } as any);

    // Clear mocks
    vi.clearAllMocks();
  });

  const renderForm = (props: Partial<React.ComponentProps<typeof CreateSiteForm>> = {}) => {
    return render(
      <QueryClientProvider client={queryClient}>
        <CreateSiteForm {...props} />
      </QueryClientProvider>
    );
  };

  describe('Rendering', () => {
    it('should render all form fields', () => {
      renderForm();

      expect(screen.getByLabelText(/domain/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/display name/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /create site/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /clear/i })).toBeInTheDocument();
    });

    it('should render with card layout by default', () => {
      renderForm();
      expect(screen.getByText('Create New Site')).toBeInTheDocument();
    });

    it('should render without card when showCard is false', () => {
      renderForm({ showCard: false });
      expect(screen.queryByText('Create New Site')).not.toBeInTheDocument();
    });

    it('should show password generation button', () => {
      renderForm();
      expect(screen.getByTitle('Generate password')).toBeInTheDocument();
    });
  });

  describe('Form Validation', () => {
    it('should show error when domain is empty', async () => {
      const user = userEvent.setup();
      renderForm();

      const submitButton = screen.getByRole('button', { name: /create site/i });
      await user.click(submitButton);

      await waitFor(() => {
        expect(screen.getByText(/domain is required/i)).toBeInTheDocument();
      });
    });

    it('should show error when domain is too short', async () => {
      const user = userEvent.setup();
      renderForm();

      const domainInput = screen.getByLabelText(/domain/i);
      await user.type(domainInput, 'ab');

      const submitButton = screen.getByRole('button', { name: /create site/i });
      await user.click(submitButton);

      await waitFor(() => {
        expect(screen.getByText(/at least 3 characters/i)).toBeInTheDocument();
      });
    });

    it('should show error when domain contains invalid characters', async () => {
      const user = userEvent.setup();
      renderForm();

      const domainInput = screen.getByLabelText(/domain/i);
      await user.type(domainInput, 'invalid_domain');

      const submitButton = screen.getByRole('button', { name: /create site/i });
      await user.click(submitButton);

      await waitFor(() => {
        expect(screen.getByText(/can only contain letters/i)).toBeInTheDocument();
      });
    });

    it('should show error when display name is empty', async () => {
      const user = userEvent.setup();
      renderForm();

      const submitButton = screen.getByRole('button', { name: /create site/i });
      await user.click(submitButton);

      await waitFor(() => {
        expect(screen.getByText(/display name is required/i)).toBeInTheDocument();
      });
    });

    it('should show error when password is too short', async () => {
      const user = userEvent.setup();
      renderForm();

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'short');

      const submitButton = screen.getByRole('button', { name: /create site/i });
      await user.click(submitButton);

      await waitFor(() => {
        expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument();
      });
    });

    it('should accept valid form data', async () => {
      const user = userEvent.setup();
      renderForm();

      await user.type(screen.getByLabelText(/domain/i), 'valid-domain');
      await user.type(screen.getByLabelText(/display name/i), 'Valid Site');
      await user.type(screen.getByLabelText(/password/i), 'validPass123');

      const submitButton = screen.getByRole('button', { name: /create site/i });
      expect(submitButton).toBeEnabled();
    });
  });

  describe('Password Generation', () => {
    it('should generate password when generation button is clicked', async () => {
      const user = userEvent.setup();
      renderForm();

      const generateButton = screen.getByTitle('Generate password');
      await user.click(generateButton);

      const passwordInput = screen.getByLabelText(/password/i) as HTMLInputElement;
      expect(passwordInput.value).toBe('Generated1234');
    });

    it('should show generated password in alert', async () => {
      const user = userEvent.setup();
      renderForm();

      const generateButton = screen.getByTitle('Generate password');
      await user.click(generateButton);

      await waitFor(() => {
        expect(screen.getByText(/password generated/i)).toBeInTheDocument();
        expect(screen.getByText(/Generated1234/)).toBeInTheDocument();
      });
    });

    it('should show copy button after password generation', async () => {
      const user = userEvent.setup();
      renderForm();

      // Initially no copy button
      expect(screen.queryByTitle('Copy password')).not.toBeInTheDocument();

      const generateButton = screen.getByTitle('Generate password');
      await user.click(generateButton);

      // Copy button appears
      expect(screen.getByTitle('Copy password')).toBeInTheDocument();
    });
  });

  describe('Password Copy Functionality', () => {
    it.skip('should copy password to clipboard', async () => {
      const user = userEvent.setup();
      renderForm();

      // Generate password first
      await user.click(screen.getByTitle('Generate password'));

      // Click copy button
      await user.click(screen.getByTitle('Copy password'));

      await waitFor(() => {
        expect(mockWriteText).toHaveBeenCalledWith('Generated1234');
      });
    });

    it('should show success icon after copying', async () => {
      const user = userEvent.setup();
      renderForm();

      await user.click(screen.getByTitle('Generate password'));
      await user.click(screen.getByTitle('Copy password'));

      await waitFor(() => {
        // Check for the Check icon (success state)
        const copyButton = screen.getByTitle('Copy password');
        expect(copyButton.querySelector('svg.lucide-check')).toBeInTheDocument();
      });
    });

    it.skip('should copy manually typed password', async () => {
      const user = userEvent.setup();
      renderForm();

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'manualPassword');

      // Copy button should appear
      const copyButton = screen.getByTitle('Copy password');
      await user.click(copyButton);

      await waitFor(() => {
        expect(mockWriteText).toHaveBeenCalledWith('manualPassword');
      });
    });
  });

  describe('Form Submission', () => {
    it('should submit form with valid data', async () => {
      const user = userEvent.setup();
      const mockResult = {
        site: {
          id: '123',
          accountId: 'acc-123',
          domain: 'test-site',
          name: 'Test Site',
          isActive: true,
          createdAt: '2025-01-01T00:00:00Z',
        },
        password: 'testPassword123',
        siteIdentifier: 'acc-123_test-site',
      };

      mockMutateAsync.mockResolvedValue(mockResult);
      renderForm();

      await user.type(screen.getByLabelText(/domain/i), 'test-site');
      await user.type(screen.getByLabelText(/display name/i), 'Test Site');
      await user.type(screen.getByLabelText(/password/i), 'testPassword123');

      await user.click(screen.getByRole('button', { name: /create site/i }));

      await waitFor(() => {
        expect(mockMutateAsync).toHaveBeenCalledWith({
          domain: 'test-site',
          displayName: 'Test Site',
          password: 'testPassword123',
        });
      });
    });

    it('should submit with generated password when no password provided', async () => {
      const user = userEvent.setup();
      mockMutateAsync.mockResolvedValue({
        site: {
          id: '123',
          accountId: 'acc-123',
          domain: 'test-site',
          name: 'Test Site',
          isActive: true,
          createdAt: '2025-01-01T00:00:00Z',
        },
        password: 'GeneratedPassword',
        siteIdentifier: 'acc-123_test-site',
      });

      renderForm();

      await user.type(screen.getByLabelText(/domain/i), 'test-site');
      await user.type(screen.getByLabelText(/display name/i), 'Test Site');

      await user.click(screen.getByRole('button', { name: /create site/i }));

      await waitFor(() => {
        expect(mockMutateAsync).toHaveBeenCalledWith({
          domain: 'test-site',
          displayName: 'Test Site',
          password: undefined,
        });
      });
    });

    it('should call onSuccess callback after successful submission', async () => {
      const user = userEvent.setup();
      const mockOnSuccess = vi.fn();
      const mockResult = {
        site: {
          id: '123',
          accountId: 'acc-123',
          domain: 'test-site',
          name: 'Test Site',
          isActive: true,
          createdAt: '2025-01-01T00:00:00Z',
        },
        password: 'testPassword123',
        siteIdentifier: 'acc-123_test-site',
      };

      mockMutateAsync.mockResolvedValue(mockResult);
      renderForm({ onSuccess: mockOnSuccess });

      await user.type(screen.getByLabelText(/domain/i), 'test-site');
      await user.type(screen.getByLabelText(/display name/i), 'Test Site');
      await user.type(screen.getByLabelText(/password/i), 'testPassword123');

      await user.click(screen.getByRole('button', { name: /create site/i }));

      await waitFor(() => {
        expect(mockOnSuccess).toHaveBeenCalledWith(mockResult);
      });
    });

    it('should reset form after successful submission', async () => {
      const user = userEvent.setup();
      mockMutateAsync.mockResolvedValue({
        site: {
          id: '123',
          accountId: 'acc-123',
          domain: 'test-site',
          name: 'Test Site',
          isActive: true,
          createdAt: '2025-01-01T00:00:00Z',
        },
        password: 'testPassword123',
        siteIdentifier: 'acc-123_test-site',
      });

      renderForm();

      const domainInput = screen.getByLabelText(/domain/i) as HTMLInputElement;
      const displayNameInput = screen.getByLabelText(/display name/i) as HTMLInputElement;
      const passwordInput = screen.getByLabelText(/password/i) as HTMLInputElement;

      await user.type(domainInput, 'test-site');
      await user.type(displayNameInput, 'Test Site');
      await user.type(passwordInput, 'testPassword123');

      await user.click(screen.getByRole('button', { name: /create site/i }));

      await waitFor(() => {
        expect(domainInput.value).toBe('');
        expect(displayNameInput.value).toBe('');
        expect(passwordInput.value).toBe('');
      });
    });

    it('should handle submission error', async () => {
      const user = userEvent.setup();
      const mockError = {
        response: {
          data: {
            message: 'Domain already exists',
          },
        },
      };

      mockMutateAsync.mockRejectedValue(mockError);
      renderForm();

      await user.type(screen.getByLabelText(/domain/i), 'test-site');
      await user.type(screen.getByLabelText(/display name/i), 'Test Site');
      await user.type(screen.getByLabelText(/password/i), 'testPassword123');

      await user.click(screen.getByRole('button', { name: /create site/i }));

      // Form should not be reset on error
      await waitFor(() => {
        const domainInput = screen.getByLabelText(/domain/i) as HTMLInputElement;
        expect(domainInput.value).toBe('test-site');
      });
    });
  });

  describe('Clear Button', () => {
    it('should clear all form fields when clicked', async () => {
      const user = userEvent.setup();
      renderForm();

      const domainInput = screen.getByLabelText(/domain/i) as HTMLInputElement;
      const displayNameInput = screen.getByLabelText(/display name/i) as HTMLInputElement;
      const passwordInput = screen.getByLabelText(/password/i) as HTMLInputElement;

      await user.type(domainInput, 'test-site');
      await user.type(displayNameInput, 'Test Site');
      await user.type(passwordInput, 'testPassword123');

      await user.click(screen.getByRole('button', { name: /clear/i }));

      expect(domainInput.value).toBe('');
      expect(displayNameInput.value).toBe('');
      expect(passwordInput.value).toBe('');
    });

    it('should clear generated password alert', async () => {
      const user = userEvent.setup();
      renderForm();

      await user.click(screen.getByTitle('Generate password'));
      expect(screen.getByText(/password generated/i)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /clear/i }));

      expect(screen.queryByText(/password generated/i)).not.toBeInTheDocument();
    });
  });

  describe('Loading State', () => {
    it('should disable inputs during submission', async () => {
      // Mock pending state
      vi.mocked(queries.useCreateSite).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: true,
        isError: false,
        isSuccess: false,
        error: null,
        data: undefined,
        mutate: vi.fn(),
        reset: vi.fn(),
        status: 'pending',
        variables: undefined,
        context: undefined,
        failureCount: 0,
        failureReason: null,
        isPaused: false,
        submittedAt: Date.now(),
      } as any);

      renderForm();

      const domainInput = screen.getByLabelText(/domain/i);
      const submitButton = screen.getByRole('button', { name: /create site/i });

      expect(domainInput).toBeDisabled();
      expect(submitButton).toBeDisabled();
    });
  });
});
