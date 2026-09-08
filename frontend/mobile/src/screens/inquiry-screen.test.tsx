import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const resolveScan = vi.fn()
const getUnitLoadsByLocation = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    resolveScan: (...a: unknown[]) => resolveScan(...a),
    getUnitLoadsByLocation: (...a: unknown[]) => getUnitLoadsByLocation(...a),
  },
}))
vi.mock('@/lib/api-client', () => ({
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { error: vi.fn() },
}))
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan }: { onScan: (c: string) => void }) => (
    <button onClick={() => onScan('UL-1')}>simulate-scan</button>
  ),
}))

import { InquiryScreen } from '@/screens/inquiry-screen'

describe('InquiryScreen', () => {
  beforeEach(() => {
    resolveScan.mockReset()
    getUnitLoadsByLocation.mockReset()
  })

  it('renders a unit load card after a scan', async () => {
    resolveScan.mockResolvedValue({
      kind: 'unitLoad',
      ul: { id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [{ id: 1, itemDataNumber: 'SKU-9', amount: 5, state: 300 }] },
    })
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(screen.getByText('UL-1')).toBeInTheDocument())
    expect(screen.getByText('SKU-9')).toBeInTheDocument()
    expect(screen.getByText(/A-01-01/)).toBeInTheDocument()
  })

  it('renders a location card with lock state and occupant labels after a scan', async () => {
    resolveScan.mockResolvedValue({ kind: 'location', location: { id: 3, name: 'A-01-01', lockType: 1, lockTypeName: 'GENERAL' } })
    getUnitLoadsByLocation.mockResolvedValue([{ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] }])
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(screen.getByText('A-01-01')).toBeInTheDocument())
    expect(await screen.findByText('LOCKED')).toBeInTheDocument()
    expect(await screen.findByText('UL-1')).toBeInTheDocument()
    expect(getUnitLoadsByLocation).toHaveBeenCalledWith(3)
  })

  it('renders an unlocked location without a LOCKED badge', async () => {
    resolveScan.mockResolvedValue({ kind: 'location', location: { id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' } })
    getUnitLoadsByLocation.mockResolvedValue([])
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(screen.getByText('A-01-01')).toBeInTheDocument())
    expect(screen.queryByText('LOCKED')).not.toBeInTheDocument()
  })

  it('renders a none card for an unrecognized scan', async () => {
    resolveScan.mockResolvedValue({ kind: 'none', code: 'GHOST' })
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(screen.getByText(/GHOST/)).toBeInTheDocument())
    expect(screen.getByText(/Not a unit load or location/)).toBeInTheDocument()
  })

  it('clears previous occupants when a new location resolves but its occupants fetch fails', async () => {
    const { toast } = await import('@/lib/api-client')
    resolveScan.mockResolvedValueOnce({ kind: 'location', location: { id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' } })
    getUnitLoadsByLocation.mockResolvedValueOnce([{ id: 7, labelId: 'UL-OLD', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] }])
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    expect(await screen.findByText('UL-OLD')).toBeInTheDocument()

    resolveScan.mockResolvedValueOnce({ kind: 'location', location: { id: 5, name: 'B-02-02', lockType: 0, lockTypeName: 'UNLOCKED' } })
    getUnitLoadsByLocation.mockRejectedValueOnce(new Error('network down'))
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(screen.getByText('B-02-02')).toBeInTheDocument())
    expect(screen.queryByText('UL-OLD')).not.toBeInTheDocument()
    expect(screen.getByText('No unit loads on this location')).toBeInTheDocument()
    expect(toast.error).toHaveBeenCalledWith('Could not load unit loads for this location')
  })

  it('surfaces a lookup error via toast', async () => {
    const { toast, ApiError } = await import('@/lib/api-client')
    resolveScan.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Error', status: 500, detail: 'boom' }))
    render(<MemoryRouter><InquiryScreen /></MemoryRouter>)
    screen.getByText('simulate-scan').click()
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('boom'))
  })
})
