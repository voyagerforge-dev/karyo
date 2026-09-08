import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const getUnitLoadByLabel = vi.fn()
const printLabel = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    getUnitLoadByLabel: (...a: unknown[]) => getUnitLoadByLabel(...a),
    printLabel: (...a: unknown[]) => printLabel(...a),
  },
}))
vi.mock('@/lib/api-client', () => ({
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { success: vi.fn(), error: vi.fn() },
}))
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan }: { onScan: (c: string) => void }) => <button onClick={() => onScan('UL-1')}>scan</button>,
}))

import { ReprintScreen } from '@/screens/reprint-screen'

describe('ReprintScreen', () => {
  beforeEach(() => {
    getUnitLoadByLabel.mockReset()
    printLabel.mockReset()
  })

  it('scans a UL and prints its label', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    printLabel.mockResolvedValue(undefined)
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))
    expect(screen.getByText(/EURO @ A-01-01/)).toBeInTheDocument()
    screen.getByText('PRINT').click()
    await waitFor(() => expect(printLabel).toHaveBeenCalledWith(7))
    const { toast } = await import('@/lib/api-client')
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Label sent to printer'))
  })

  it('surfaces a not-found toast when the scanned code has no unit load', async () => {
    getUnitLoadByLabel.mockRejectedValue(new Error('boom'))
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    const { toast } = await import('@/lib/api-client')
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Unit load "UL-1" not found'))
    expect(screen.queryByText('PRINT')).not.toBeInTheDocument()
  })

  it('surfaces the printer problem detail when printing fails (e.g. no printer configured)', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    const { ApiError, toast } = await import('@/lib/api-client')
    printLabel.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Service Unavailable', status: 503, detail: 'No printer configured' }))
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))
    screen.getByText('PRINT').click()
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('No printer configured'))
  })

  it('disables PRINT (and shows PRINTING…) while a print is in flight, so a double-tap does not fire a second POST', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    let resolvePrint!: () => void
    printLabel.mockReturnValue(new Promise<void>((resolve) => { resolvePrint = resolve }))
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))

    fireEvent.click(screen.getByText('PRINT'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'PRINTING…' })).toBeDisabled())
    // A second tap while the first call is still unresolved must be a no-op: the disabled
    // attribute already suppresses the click, but assert the call count directly too.
    fireEvent.click(screen.getByRole('button', { name: 'PRINTING…' }))
    expect(printLabel).toHaveBeenCalledTimes(1)

    resolvePrint()
    await waitFor(() => expect(screen.getByRole('button', { name: 'PRINT' })).not.toBeDisabled())
    expect(printLabel).toHaveBeenCalledTimes(1)
  })

  it('lets the operator scan a different unit load without leaving the screen', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] })
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('UL-1'))
    fireEvent.click(screen.getByText('Different unit load'))
    expect(screen.queryByText('UL-1')).not.toBeInTheDocument()
    expect(screen.getByText('scan')).toBeInTheDocument()
  })

  it('renders a back to menu button', () => {
    render(<MemoryRouter><ReprintScreen /></MemoryRouter>)
    expect(screen.getByText('Back to menu')).toBeInTheDocument()
  })
})
