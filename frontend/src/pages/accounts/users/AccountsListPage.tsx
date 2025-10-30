/**
 * AccountsListPage - User Management
 *
 * Per T032: Accounts list page with Keycloak integration and filters.
 * Displays users with their Keycloak status and management actions.
 *
 * Features:
 * - UserListTable with Keycloak fields
 * - Filters: isActive, hasKeycloakIntegration, passwordTemporary
 * - Search by name/email
 * - Create Account button → CreateAccountPage
 * - Row click → AccountDetailsPage (future)
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
  const [filterKeycloak, setFilterKeycloak] = useState<boolean | 'all'>('all')
  const [filterTempPassword, setFilterTempPassword] = useState<boolean | 'all'>('all')
  const [showFilters, setShowFilters] = useState(false)

  const { data, isLoading, isError, error } = useAccountsQuery(page - 1, pageSize, {
    search: search || undefined,
  })

  // Filter data client-side (TODO: move to server-side filtering)
  const filteredUsers = data?.content.filter((user) => {
    if (filterActive !== 'all' && user.isActive !== filterActive) return false
    if (filterKeycloak !== 'all') {
      const hasKeycloak = !!user.keycloakUserId
      if (hasKeycloak !== filterKeycloak) return false
    }
    if (filterTempPassword !== 'all' && user.passwordTemporary !== filterTempPassword) return false
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
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">User Management</h1>
            <p className="mt-2 text-sm text-gray-600">
              Manage users with Keycloak authentication integration
            </p>
          </div>
          <button
            onClick={() => navigate({ to: '/admin/users/create' })}
            className="flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
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
              className="flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              <Filter className="h-4 w-4" />
              Filters
              {(filterActive !== 'all' || filterKeycloak !== 'all' || filterTempPassword !== 'all') && (
                <span className="ml-1 rounded-full bg-blue-600 px-2 py-0.5 text-xs text-white">
                  Active
                </span>
              )}
            </button>
          </div>

          {/* Filter Panel */}
          {showFilters && (
            <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
              <h3 className="mb-3 text-sm font-medium text-gray-900">Filter Options</h3>
              <div className="grid gap-4 sm:grid-cols-3">
                {/* Active Status Filter */}
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">
                    Account Status
                  </label>
                  <select
                    value={filterActive === 'all' ? 'all' : filterActive ? 'true' : 'false'}
                    onChange={(e) =>
                      setFilterActive(
                        e.target.value === 'all' ? 'all' : e.target.value === 'true'
                      )
                    }
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    <option value="all">All</option>
                    <option value="true">Active</option>
                    <option value="false">Inactive</option>
                  </select>
                </div>

                {/* Keycloak Integration Filter */}
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">
                    Keycloak Integration
                  </label>
                  <select
                    value={filterKeycloak === 'all' ? 'all' : filterKeycloak ? 'true' : 'false'}
                    onChange={(e) =>
                      setFilterKeycloak(
                        e.target.value === 'all' ? 'all' : e.target.value === 'true'
                      )
                    }
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    <option value="all">All</option>
                    <option value="true">Has Keycloak</option>
                    <option value="false">No Keycloak</option>
                  </select>
                </div>

                {/* Password Temporary Filter */}
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">
                    Password Type
                  </label>
                  <select
                    value={filterTempPassword === 'all' ? 'all' : filterTempPassword ? 'true' : 'false'}
                    onChange={(e) =>
                      setFilterTempPassword(
                        e.target.value === 'all' ? 'all' : e.target.value === 'true'
                      )
                    }
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    <option value="all">All</option>
                    <option value="true">Temporary</option>
                    <option value="false">Permanent</option>
                  </select>
                </div>
              </div>

              {/* Clear Filters */}
              {(filterActive !== 'all' || filterKeycloak !== 'all' || filterTempPassword !== 'all') && (
                <button
                  onClick={() => {
                    setFilterActive('all')
                    setFilterKeycloak('all')
                    setFilterTempPassword('all')
                  }}
                  className="mt-3 text-sm text-blue-600 hover:text-blue-700"
                >
                  Clear all filters
                </button>
              )}
            </div>
          )}
        </div>

        {/* Error state */}
        {isError && (
          <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4">
            <p className="text-sm font-medium text-red-800">
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
