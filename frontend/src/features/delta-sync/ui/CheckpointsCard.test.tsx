import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CheckpointsCard } from './CheckpointsCard'
import type { DeltaCheckpoint } from '../model/types'

const NOW = new Date('2026-07-05T12:00:00Z')

function checkpoint(partial: Partial<DeltaCheckpoint> & { table: string }): DeltaCheckpoint {
  return {
    seq: 4821,
    rowCount: 1_204_500,
    updatedAt: '2026-07-05T09:00:00Z',
    hasCsv: true,
    hasParquet: true,
    ...partial,
  }
}

const checkpoints: DeltaCheckpoint[] = [
  checkpoint({ table: 'orders' }),
  checkpoint({ table: 'customers', hasParquet: false }),
  checkpoint({ table: 'inventory', hasCsv: false, hasParquet: false, rowCount: 12 }),
  checkpoint({ table: 'payments', updatedAt: '2026-07-03T09:00:00Z' }), // stale (>24h)
]

describe('CheckpointsCard (F7)', () => {
  it('renders the table view with formatted numbers by default', () => {
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )

    expect(screen.getByText('Materialized snapshot per table · download links valid 15 minutes')).toBeInTheDocument()
    const ordersRow = screen.getByTestId('checkpoint-row-orders')
    expect(within(ordersRow).getByText('1,204,500')).toBeInTheDocument()
    expect(within(ordersRow).getByText('4,821')).toBeInTheDocument()
  })

  it('marks tables not updated for over 24h with a stale pill', () => {
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )

    const paymentsRow = screen.getByTestId('checkpoint-row-payments')
    expect(within(paymentsRow).getByText('stale')).toBeInTheDocument()
    expect(within(screen.getByTestId('checkpoint-row-orders')).queryByText('stale')).not.toBeInTheDocument()
  })

  it('renders file pills per D1: Parquet primary, dashed pending, muted legacy CSV, em-dash', () => {
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )

    const orders = screen.getByTestId('checkpoint-row-orders')
    expect(within(orders).getByRole('button', { name: 'Parquet' })).toBeInTheDocument()
    expect(within(orders).getByRole('button', { name: 'CSV' })).toHaveAttribute('title', 'Legacy · used by Bit BI')

    const customers = screen.getByTestId('checkpoint-row-customers')
    const pending = within(customers).getByText('Parquet pending')
    expect(pending.tagName).not.toBe('BUTTON')
    expect(pending).toHaveAttribute('title', 'Full Parquet snapshot has not been materialized yet')

    const inventory = screen.getByTestId('checkpoint-row-inventory')
    expect(within(inventory).getByText('—')).toBeInTheDocument()
  })

  it('requests a download with the clicked format', async () => {
    const onDownload = vi.fn()
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={onDownload} now={NOW} />,
    )

    await userEvent.click(within(screen.getByTestId('checkpoint-row-orders')).getByRole('button', { name: 'Parquet' }))
    expect(onDownload).toHaveBeenCalledWith('orders', 'parquet')

    await userEvent.click(within(screen.getByTestId('checkpoint-row-customers')).getByRole('button', { name: 'CSV' }))
    expect(onDownload).toHaveBeenCalledWith('customers', 'csv')
  })

  it('switches to the cards view with the relative-weight bar', async () => {
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )

    await userEvent.click(screen.getByRole('tab', { name: /cards/i }))
    const cards = screen.getByTestId('checkpoints-cards-view')
    expect(within(cards).getByText('no files yet')).toBeInTheDocument()
  })

  it('shows the rebuild button only for canManage and disables it while queued', () => {
    const { rerender } = render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )
    expect(screen.queryByRole('button', { name: /rebuild checkpoint now/i })).not.toBeInTheDocument()

    rerender(
      <CheckpointsCard
        checkpoints={checkpoints}
        canManage={true}
        onDownload={vi.fn()}
        rebuildQueued={true}
        now={NOW}
      />,
    )
    expect(screen.getByRole('button', { name: /rebuild checkpoint now/i })).toBeDisabled()
  })

  it('shows a client-side name filter beyond 15 tables and filters rows', async () => {
    const many = Array.from({ length: 17 }, (_, i) => checkpoint({ table: `table_${String(i).padStart(2, '0')}` }))
    render(<CheckpointsCard checkpoints={many} canManage={false} onDownload={vi.fn()} now={NOW} />)

    const filterInput = screen.getByLabelText('Filter tables')
    await userEvent.type(filterInput, 'table_07')

    expect(screen.getByTestId('checkpoint-row-table_07')).toBeInTheDocument()
    expect(screen.queryByTestId('checkpoint-row-table_08')).not.toBeInTheDocument()
  })

  it('hides the filter for small sites', () => {
    render(
      <CheckpointsCard checkpoints={checkpoints} canManage={false} onDownload={vi.fn()} now={NOW} />,
    )
    expect(screen.queryByLabelText('Filter tables')).not.toBeInTheDocument()
  })
})
