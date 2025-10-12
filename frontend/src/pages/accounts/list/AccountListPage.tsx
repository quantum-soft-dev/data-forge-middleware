/**
 * Account List Page
 *
 * Displays paginated list of accounts with search and filter capabilities.
 * Per spec FR-010: View account list with pagination, search, and filter.
 */

import { useState } from 'react'
import { Plus } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { AccountTable } from '@/widgets/account-table/AccountTable'
import { SearchInput } from '@/features/account-search/SearchInput'
import { StatusFilter } from '@/features/account-search/StatusFilter'
import { CreateAccountDialog } from '@/features/account-create/CreateAccountDialog'
import { EditAccountDialog } from '@/features/account-edit/EditAccountDialog'
import { DeleteAccountDialog } from '@/features/account-delete/DeleteAccountDialog'
import { useAccounts } from '@/entities/account/api/useAccounts'
import type { Account, AccountStatus } from '@/entities/account/model/types'

export default function AccountListPage() {
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<AccountStatus | 'all'>('all')
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false)
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false)
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false)
  const [selectedAccount, setSelectedAccount] = useState<Account | null>(null)

  const { data, isLoading, isError, error } = useAccounts({
    page,
    pageSize,
    search: search || undefined,
    status: status === 'all' ? undefined : status,
  })

  const handlePageChange = (newPage: number) => {
    setPage(newPage)
  }

  const handlePageSizeChange = (newSize: number) => {
    setPageSize(newSize)
    setPage(1) // Reset to first page on size change
  }

  const handleSearch = (query: string) => {
    setSearch(query)
    setPage(1) // Reset to first page on search
  }

  const handleStatusChange = (newStatus: AccountStatus | 'all') => {
    setStatus(newStatus)
    setPage(1) // Reset to first page on filter change
  }

  const handleEdit = (account: Account) => {
    setSelectedAccount(account)
    setIsEditDialogOpen(true)
  }

  const handleDelete = (account: Account) => {
    setSelectedAccount(account)
    setIsDeleteDialogOpen(true)
  }

  const handleCloseEditDialog = () => {
    setIsEditDialogOpen(false)
    setSelectedAccount(null)
  }

  const handleCloseDeleteDialog = () => {
    setIsDeleteDialogOpen(false)
    setSelectedAccount(null)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">Accounts</h1>
            <p className="mt-2 text-sm text-gray-600">
              Manage your account database
            </p>
          </div>
          <button
            onClick={() => setIsCreateDialogOpen(true)}
            className="flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
          >
            <Plus className="h-4 w-4" />
            Create Account
          </button>
        </div>

        {/* Filters */}
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="w-full sm:w-96">
            <SearchInput onSearch={handleSearch} />
          </div>
          <StatusFilter value={status} onChange={handleStatusChange} />
        </div>

        {/* Error state */}
        {isError && (
          <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4">
            <p className="text-sm font-medium text-red-800">
              {error instanceof Error ? error.message : 'Failed to load accounts'}
            </p>
          </div>
        )}

        {/* Table */}
        <AccountTable
          accounts={data?.content ?? []}
          totalCount={data?.totalElements ?? 0}
          page={page}
          pageSize={pageSize}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          onEdit={handleEdit}
          onDelete={handleDelete}
          isLoading={isLoading}
        />

        {/* Create Dialog */}
        <CreateAccountDialog
          open={isCreateDialogOpen}
          onClose={() => setIsCreateDialogOpen(false)}
        />

        {/* Edit Dialog */}
        <EditAccountDialog
          account={selectedAccount}
          open={isEditDialogOpen}
          onClose={handleCloseEditDialog}
        />

        {/* Delete Dialog */}
        <DeleteAccountDialog
          account={selectedAccount}
          open={isDeleteDialogOpen}
          onClose={handleCloseDeleteDialog}
        />
      </main>
    </div>
  )
}
