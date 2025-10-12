import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AccountTable } from '@/widgets/account-table/AccountTable'
import type { Account } from '@/entities/account/model/types'

// Mock data
const mockAccounts: Account[] = [
  {
    id: '1',
    name: 'John Doe',
    email: 'john@example.com',
    phone: '+1234567890',
    company: 'Acme Corp',
    status: 'active',
    createdAt: '2024-01-15T10:00:00Z',
  },
  {
    id: '2',
    name: 'Jane Smith',
    email: 'jane@example.com',
    phone: '+9876543210',
    company: 'TechStart Inc',
    status: 'inactive',
    createdAt: '2024-02-20T14:30:00Z',
  },
]

describe('AccountTable', () => {
  it('should render table with account data', () => {
    render(
      <AccountTable
        accounts={mockAccounts}
        totalCount={2}
        page={1}
        pageSize={10}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />
    )

    expect(screen.getByText('John Doe')).toBeInTheDocument()
    expect(screen.getByText('jane@example.com')).toBeInTheDocument()
  })

  it('should render all table columns', () => {
    render(
      <AccountTable
        accounts={mockAccounts}
        totalCount={2}
        page={1}
        pageSize={10}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />
    )

    // Column headers
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Email')).toBeInTheDocument()
    expect(screen.getByText('Phone')).toBeInTheDocument()
    expect(screen.getByText('Company')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
    expect(screen.getByText('Created')).toBeInTheDocument()
  })

  it('should render pagination controls', () => {
    render(
      <AccountTable
        accounts={mockAccounts}
        totalCount={25}
        page={1}
        pageSize={10}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />
    )

    // Pagination should show (e.g., "Page 1 of 3")
    const pageTexts = screen.getAllByText(/Page/i)
    expect(pageTexts.length).toBeGreaterThan(0)
  })

  it('should call onPageChange when clicking pagination buttons', () => {
    const onPageChange = vi.fn()
    render(
      <AccountTable
        accounts={mockAccounts}
        totalCount={25}
        page={1}
        pageSize={10}
        onPageChange={onPageChange}
        onPageSizeChange={vi.fn()}
      />
    )

    const nextButton = screen.getByRole('button', { name: /next/i })
    nextButton.click()

    expect(onPageChange).toHaveBeenCalledWith(2)
  })

  it('should display empty state when no accounts', () => {
    render(
      <AccountTable
        accounts={[]}
        totalCount={0}
        page={1}
        pageSize={10}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />
    )

    expect(screen.getByText(/No accounts found/i)).toBeInTheDocument()
  })

  it('should display loading skeleton when loading', () => {
    render(
      <AccountTable
        accounts={[]}
        totalCount={0}
        page={1}
        pageSize={10}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
        isLoading={true}
      />
    )

    // Should show skeleton rows (data-testid or class)
    const skeletons = screen.getAllByTestId(/skeleton/i)
    expect(skeletons.length).toBeGreaterThan(0)
  })
})
