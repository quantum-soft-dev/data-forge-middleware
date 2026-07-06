/**
 * AccountsListPage - User Management
 *
 * Per T032: Accounts list page with Auth0 integration and filters.
 * Displays users with their Auth0 status and management actions.
 *
 * Features:
 * - UserListTable with Auth0 fields
 * - Filters: isActive, hasAuth0Integration
 * - Search by name/email
 * - Create Account button → CreateAccountPage
 * - Row click → AccountDetailsPage
 * - Pagination
 */

import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Plus, Filter } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { UserListTable } from '@/widgets/user-management/UserListTable'
import { SearchInput } from '@/features/account-search/SearchInput'
import { useAccountsQuery } from '@/features/user-management/api/userQueries'
import type { AccountWithKeycloakStatus } from '@/entities/account/model/types'

export default function AccountsListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [search, setSearch] = useState('')

  // Filters
  const [filterActive, setFilterActive] = useState<boolean | 'all'>('all')
  const [filterAuth0, setFilterAuth0] = useState<boolean | 'all'>('all')
  const [showFilters, setShowFilters] = useState(false)

  const { data, isLoading, isError, error } = useAccountsQuery(page - 1, pageSize, {
    search: search || undefined,
  })

  // Filter data client-side (TODO: move to server-side filtering)
  const filteredUsers = data?.content.filter((user) => {
    if (filterActive !== 'all' && user.isActive !== filterActive) return false
    if (filterAuth0 !== 'all') {
      const hasAuth0 = !!user.identityProviderUserId
      if (hasAuth0 !== filterAuth0) return false
    }
    return true
  }) ?? []

  const handlePageChange = (newPage: number) => {
    setPage(newPage)
  }

  const handlePageSizeChange = (newSize: number) => {
    setPageSize(newSize)
    setPage(1)
  }

  const handleSearch = (query: string) => {
    setSearch(query)
    setPage(1)
  }

  const handleViewDetails = (user: AccountWithKeycloakStatus) => {
    navigate({ to: '/admin/users/$id', params: { id: user.id } })
  }

  const handleLock = (user: AccountWithKeycloakStatus) => {
    // TODO: Implement lock functionality (T040-T045 - User Story 2)
    console.log('Lock user:', user.id)
  }

  const handleUnlock = (user: AccountWithKeycloakStatus) => {
    // TODO: Implement unlock functionality (T040-T045 - User Story 2)
    console.log('Unlock user:', user.id)
  }

  const handleResetPassword = (user: AccountWithKeycloakStatus) => {
    // TODO: Implement reset password functionality (T054-T059 - User Story 3)
    console.log('Reset password for user:', user.id)
  }

  return (
    <div className="min-h-screen bg-surface-hover">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-[22px] font-medium leading-[1.1] tracking-[-0.33px] text-ink">User Management</h1>
            <p className="mt-2 text-sm text-ink-secondary">
              Manage users with Auth0 authentication integration
            </p>
          </div>
          <button
            onClick={() => navigate({ to: '/admin/users/create' })}
            className="flex items-center gap-2 rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-hover focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
          >
            <Plus className="h-4 w-4" />
            Create Account
          </button>
        </div>

        {/* Search and filters */}
        <div className="mb-6 space-y-4">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="w-full sm:w-96">
              <SearchInput onSearch={handleSearch} />
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className="flex items-center gap-2 rounded-lg border border-hairline bg-white px-4 py-2 text-sm font-medium text-ink hover:bg-secondary"
            >
              <Filter className="h-4 w-4" />
              Filters
              {(filterActive !== 'all' || filterAuth0 !== 'all') && (
                <span className="ml-1 rounded-full bg-brand px-2 py-0.5 text-xs text-white">
                  Active
                </span>
              )}
            </button>
          </div>

          {/* Filter Panel */}
          {showFilters && (
            <div className="rounded-lg bg-white p-4 shadow-card">
              <h3 className="mb-3 text-sm font-medium text-ink">Filter Options</h3>
              <div className="grid gap-4 sm:grid-cols-2">
                {/* Active Status Filter */}
                <div>
                  <label className="block text-xs font-medium text-ink-secondary mb-1">
                    Account Status
                  </label>
                  <select
                    value={filterActive === 'all' ? 'all' : filterActive ? 'true' : 'false'}
                    onChange={(e) =>
                      setFilterActive(
                        e.target.value === 'all' ? 'all' : e.target.value === 'true'
                      )
                    }
                    className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
                  >
                    <option value="all">All</option>
                    <option value="true">Active</option>
                    <option value="false">Inactive</option>
                  </select>
                </div>

                {/* Auth0 Integration Filter */}
                <div>
                  <label className="block text-xs font-medium text-ink-secondary mb-1">
                    Auth0 Integration
                  </label>
                  <select
                    value={filterAuth0 === 'all' ? 'all' : filterAuth0 ? 'true' : 'false'}
                    onChange={(e) =>
                      setFilterAuth0(
                        e.target.value === 'all' ? 'all' : e.target.value === 'true'
                      )
                    }
                    className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
                  >
                    <option value="all">All</option>
                    <option value="true">Has Auth0</option>
                    <option value="false">No Auth0</option>
                  </select>
                </div>
              </div>

              {/* Clear Filters */}
              {(filterActive !== 'all' || filterAuth0 !== 'all') && (
                <button
                  onClick={() => {
                    setFilterActive('all')
                    setFilterAuth0('all')
                  }}
                  className="mt-3 text-sm font-medium text-brand hover:text-brand-hover"
                >
                  Clear all filters
                </button>
              )}
            </div>
          )}
        </div>

        {/* Error state */}
        {isError && (
          <div className="mb-6 rounded-lg border border-danger-border bg-danger-bg p-4">
            <p className="text-sm font-medium text-danger-text">
              {error instanceof Error ? error.message : 'Failed to load users'}
            </p>
          </div>
        )}

        {/* Table */}
        <UserListTable
          users={filteredUsers}
          totalCount={filteredUsers.length}
          page={page}
          pageSize={pageSize}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          onViewDetails={handleViewDetails}
          onLock={handleLock}
          onUnlock={handleUnlock}
          onResetPassword={handleResetPassword}
          isLoading={isLoading}
        />
      </main>
    </div>
  )
}
