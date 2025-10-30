/**
 * Integration tests for SiteManagementPage
 *
 * Tests the complete user flow for site management:
 * - Rendering the page with CreateSiteForm and SiteList
 * - Creating a new site
 * - Viewing the site list
 * - Interacting with site actions
 *
 * Feature: 007-adding-a-site (T029, US1)
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SiteManagementPage } from './SiteManagementPage';
import * as siteApi from '@/entities/site/api/siteApi';
import type { Site } from '@/entities/site/model/types';

// Mock the site API
vi.mock('@/entities/site/api/siteApi');

// Mock the Header component
vi.mock('@/widgets/header/Header', () => ({
  Header: () => <div data-testid="mock-header">Header</div>,
}));

describe('SiteManagementPage', () => {
  let queryClient: QueryClient;

  const mockSites: Site[] = [
    {
      id: '1',
      accountId: 'account-1',
      domain: 'example.com',
      displayName: 'Example Site',
      isActive: true,
      createdAt: '2024-01-01T00:00:00Z',
    },
    {
      id: '2',
      accountId: 'account-1',
      domain: 'test.com',
      displayName: 'Test Site',
      isActive: false,
      createdAt: '2024-01-02T00:00:00Z',
    },
  ];

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
      },
    });

    // Reset mocks
    vi.clearAllMocks();
  });

  const renderPage = () => {
    return render(
      <QueryClientProvider client={queryClient}>
        <SiteManagementPage />
      </QueryClientProvider>
    );
  };

  describe('Page Rendering', () => {
    it('should render the page with Header component', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      renderPage();

      expect(screen.getByTestId('mock-header')).toBeInTheDocument();
    });

    it('should render the CreateSiteForm component', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      renderPage();

      expect(screen.getByText('Create New Site')).toBeInTheDocument();
    });

    it('should render the SiteList component', async () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue(mockSites);

      renderPage();

      await waitFor(() => {
        expect(screen.getByText('Example Site')).toBeInTheDocument();
      });
    });
  });

  describe('Site List Integration', () => {
    it('should display sites from API', async () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue(mockSites);

      renderPage();

      await waitFor(() => {
        expect(screen.getByText('Example Site')).toBeInTheDocument();
        expect(screen.getByText('example.com')).toBeInTheDocument();
        expect(screen.getByText('Test Site')).toBeInTheDocument();
        expect(screen.getByText('test.com')).toBeInTheDocument();
      });
    });

    it('should display loading state while fetching sites', () => {
      vi.mocked(siteApi.listUserSites).mockImplementation(
        () => new Promise(() => {}) // Never resolves
      );

      renderPage();

      expect(screen.getByRole('status')).toBeInTheDocument(); // Skeleton loading
    });

    it('should display error message when site fetching fails', async () => {
      vi.mocked(siteApi.listUserSites).mockRejectedValue(
        new Error('Failed to load sites')
      );

      renderPage();

      await waitFor(() => {
        expect(screen.getByText(/Failed to load sites/i)).toBeInTheDocument();
      });
    });

    it('should display empty state when no sites exist', async () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      renderPage();

      await waitFor(() => {
        expect(screen.getByText(/No sites found/i)).toBeInTheDocument();
      });
    });
  });

  describe('Create Site Integration', () => {
    it('should refresh site list after creating a new site', async () => {
      const user = userEvent.setup();

      // Initial empty list
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      // Mock successful site creation
      vi.mocked(siteApi.createUserSite).mockResolvedValue({
        site: mockSites[0],
        password: 'generated-password-123',
      });

      renderPage();

      // Wait for initial empty state
      await waitFor(() => {
        expect(screen.getByText(/No sites found/i)).toBeInTheDocument();
      });

      // Now return the created site in the next query
      vi.mocked(siteApi.listUserSites).mockResolvedValue([mockSites[0]]);

      // Fill in the form
      const domainInput = screen.getByLabelText(/domain/i);
      const displayNameInput = screen.getByLabelText(/display name/i);
      const submitButton = screen.getByRole('button', { name: /create site/i });

      await user.type(domainInput, 'example.com');
      await user.type(displayNameInput, 'Example Site');
      await user.click(submitButton);

      // Wait for the site to appear in the list
      await waitFor(() => {
        expect(screen.getByText('Example Site')).toBeInTheDocument();
        expect(screen.getByText('example.com')).toBeInTheDocument();
      });
    });

    it('should display password in dialog after site creation', async () => {
      const user = userEvent.setup();

      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);
      vi.mocked(siteApi.createUserSite).mockResolvedValue({
        site: mockSites[0],
        password: 'generated-password-123',
      });

      renderPage();

      // Fill in the form without password
      const domainInput = screen.getByLabelText(/domain/i);
      const displayNameInput = screen.getByLabelText(/display name/i);
      const submitButton = screen.getByRole('button', { name: /create site/i });

      await user.type(domainInput, 'example.com');
      await user.type(displayNameInput, 'Example Site');
      await user.click(submitButton);

      // Wait for password dialog
      await waitFor(() => {
        expect(screen.getByText(/generated-password-123/i)).toBeInTheDocument();
      });
    });
  });

  describe('Page Layout', () => {
    it('should have proper spacing between form and list', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue(mockSites);

      const { container } = renderPage();

      // Check for space-y class or similar spacing
      const main = container.querySelector('main');
      expect(main).toHaveClass('space-y-6');
    });

    it('should be responsive with max-width container', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      const { container } = renderPage();

      const main = container.querySelector('main');
      expect(main).toHaveClass('max-w-4xl');
    });
  });

  describe('Accessibility', () => {
    it('should have proper semantic structure with header, main', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      const { container } = renderPage();

      expect(container.querySelector('header')).toBeInTheDocument();
      expect(container.querySelector('main')).toBeInTheDocument();
    });

    it('should have descriptive page heading', () => {
      vi.mocked(siteApi.listUserSites).mockResolvedValue([]);

      renderPage();

      // CreateSiteForm has the heading
      expect(screen.getByText('Create New Site')).toBeInTheDocument();
    });
  });
});
