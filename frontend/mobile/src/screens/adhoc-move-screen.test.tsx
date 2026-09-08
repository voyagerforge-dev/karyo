import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const getUnitLoadByLabel = vi.fn()
const getLocationByCode = vi.fn()
const adhocMove = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    getUnitLoadByLabel: (...a: unknown[]) => getUnitLoadByLabel(...a),
    getLocationByCode: (...a: unknown[]) => getLocationByCode(...a),
    adhocMove: (...a: unknown[]) => adhocMove(...a),
  },
}))
vi.mock('@/lib/api-client', () => ({
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { success: vi.fn(), error: vi.fn() },
}))
let scans: string[] = []
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan }: { onScan: (c: string) => void }) => (
    <button onClick={() => onScan(scans.shift()!)}>scan</button>
  ),
}))

import { AdhocMoveScreen } from '@/screens/adhoc-move-screen'

describe('AdhocMoveScreen', () => {
  beforeEach(() => {
    getUnitLoadByLabel.mockReset()
    getLocationByCode.mockReset()
    adhocMove.mockReset()
  })

  it('walks source, target, confirm and calls adhocMove', async () => {
    scans = ['UL-1', 'B-02-03']
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    getLocationByCode.mockResolvedValue({ id: 31, name: 'B-02-03', lockType: 0, lockTypeName: 'UNLOCKED' })
    adhocMove.mockResolvedValue(undefined)
    render(<MemoryRouter><AdhocMoveScreen /></MemoryRouter>)
    screen.getByText('scan').click() // source
    await waitFor(() => screen.getByText('UL-1'))
    screen.getByText('scan').click() // target
    await waitFor(() => screen.getByText('B-02-03'))
    screen.getByText('CONFIRM MOVE').click()
    await waitFor(() => expect(adhocMove).toHaveBeenCalledWith(7, 31, 'B-02-03'))
    await waitFor(() => screen.getByText(/moved/i))
  })

  it('shows an error panel when the source scan does not resolve', async () => {
    scans = ['GHOST']
    const { ApiError } = await import('@/lib/api-client')
    getUnitLoadByLabel.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Not Found', status: 404, detail: 'Unit load not found' }))
    render(<MemoryRouter><AdhocMoveScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Unit load not found'))
  })

  it('shows an error panel when the target scan does not resolve, staying on the target step', async () => {
    scans = ['UL-1', 'GHOST']
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    const { ApiError } = await import('@/lib/api-client')
    getLocationByCode.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Not Found', status: 404, detail: 'Location not found' }))
    render(<MemoryRouter><AdhocMoveScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))
    screen.getByText('scan').click()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Location not found'))
    expect(screen.getByText('scan')).toBeInTheDocument()
  })

  it('shows an error panel when the move itself fails at confirm', async () => {
    scans = ['UL-1', 'B-02-03']
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    getLocationByCode.mockResolvedValue({ id: 31, name: 'B-02-03', lockType: 0, lockTypeName: 'UNLOCKED' })
    const { ApiError } = await import('@/lib/api-client')
    adhocMove.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'Move failed: location locked' }))
    render(<MemoryRouter><AdhocMoveScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('B-02-03'))
    screen.getByText('CONFIRM MOVE').click()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Move failed: location locked'))
  })

  it('renders a back to menu button', () => {
    render(<MemoryRouter><AdhocMoveScreen /></MemoryRouter>)
    expect(screen.getByText('Back to menu')).toBeInTheDocument()
  })
})
