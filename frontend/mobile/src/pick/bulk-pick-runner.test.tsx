import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const getBulkLines = vi.fn()
const bulkConfirm = vi.fn()
vi.mock('@/lib/work-api', () => ({ workApi: { getBulkLines: (...a: unknown[]) => getBulkLines(...a), bulkConfirm: (...a: unknown[]) => bulkConfirm(...a) } }))
vi.mock('@/components/hmi/scan-field', () => ({
  ScanField: ({ onMatch, onMismatch, label }: { onMatch: () => void; onMismatch: (what: string) => void; label: string }) => (
    <>
      <button onClick={onMatch}>{label}</button>
      <button onClick={() => onMismatch('X')}>{label} mismatch</button>
    </>
  ),
}))

import { BulkPickRunner } from '@/pick/bulk-pick-runner'

const line = { sourceStockUnitId: 3, locationName: 'A-01-01', unitLoadLabel: 'UL-1', itemDataNumber: 'SKU-1', lotNumber: null, plannedTotal: 40, pickedTotal: 0, openSlices: 3 }
const wrap = (ui: React.ReactElement) => <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>

describe('BulkPickRunner', () => {
  beforeEach(() => { getBulkLines.mockReset(); bulkConfirm.mockReset() })

  it('walks location -> item -> qty and confirms the aggregated line', async () => {
    getBulkLines.mockResolvedValueOnce([line]).mockResolvedValueOnce([])
    bulkConfirm.mockResolvedValue({ filledSlices: 2, shortSlices: 1 })
    const onDone = vi.fn()
    render(wrap(<BulkPickRunner pickOrderId={7} onDone={onDone} onRelease={() => {}} />))
    await waitFor(() => screen.getByText('A-01-01'))
    fireEvent.click(screen.getByText('Scan location'))
    fireEvent.click(screen.getByText('Scan item'))
    expect(screen.getByTestId('bulk-qty')).toHaveTextContent('40')
    fireEvent.change(screen.getByLabelText('quantity'), { target: { value: '27' } })
    fireEvent.click(screen.getByRole('button', { name: 'CONFIRM' }))
    await waitFor(() => expect(bulkConfirm).toHaveBeenCalledWith(7, { sourceStockUnitId: 3, pickedAmount: 27 }))
    await waitFor(() => screen.getByText(/2 orders filled, 1 short/))
    await waitFor(() => screen.getByText(/Bulk pick complete/))
  })

  it('tracks the step counter across multiple lines using the initial line count, not the shrinking array', async () => {
    const lineB = { sourceStockUnitId: 4, locationName: 'B-02-02', unitLoadLabel: 'UL-2', itemDataNumber: 'SKU-2', lotNumber: null, plannedTotal: 10, pickedTotal: 0, openSlices: 1 }
    getBulkLines.mockResolvedValueOnce([line, lineB]).mockResolvedValueOnce([lineB]).mockResolvedValueOnce([])
    bulkConfirm.mockResolvedValue({ filledSlices: 1, shortSlices: 0 })
    render(wrap(<BulkPickRunner pickOrderId={7} onDone={() => {}} onRelease={() => {}} />))

    await waitFor(() => screen.getByText('A-01-01'))
    expect(screen.getByText('1 / 2')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Scan location'))
    fireEvent.click(screen.getByText('Scan item'))
    fireEvent.click(screen.getByRole('button', { name: 'CONFIRM' }))
    await waitFor(() => expect(bulkConfirm).toHaveBeenCalledWith(7, { sourceStockUnitId: 3, pickedAmount: 40 }))

    await waitFor(() => screen.getByText('B-02-02'))
    expect(screen.getByText('2 / 2')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Scan location'))
    fireEvent.click(screen.getByText('Scan item'))
    fireEvent.click(screen.getByRole('button', { name: 'CONFIRM' }))
    await waitFor(() => expect(bulkConfirm).toHaveBeenCalledWith(7, { sourceStockUnitId: 4, pickedAmount: 10 }))

    await waitFor(() => screen.getByText(/Bulk pick complete/))
  })

  it('clears a scan-mismatch error once the later scan matches', async () => {
    getBulkLines.mockResolvedValueOnce([line])
    render(wrap(<BulkPickRunner pickOrderId={7} onDone={() => {}} onRelease={() => {}} />))
    await waitFor(() => screen.getByText('A-01-01'))

    fireEvent.click(screen.getByText('Scan location mismatch'))
    expect(screen.getByText('Wrong location, try again')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Scan location'))
    expect(screen.queryByText('Wrong location, try again')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('Scan item mismatch'))
    expect(screen.getByText('Wrong item, try again')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Scan item'))
    expect(screen.queryByText('Wrong item, try again')).not.toBeInTheDocument()
  })
})
