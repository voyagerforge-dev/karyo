import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const getUnitLoadByLabel = vi.fn()
const findPickOrderByTargetUl = vi.fn()
const openShipment = vi.fn()
const packShipment = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    getUnitLoadByLabel: (...a: unknown[]) => getUnitLoadByLabel(...a),
    findPickOrderByTargetUl: (...a: unknown[]) => findPickOrderByTargetUl(...a),
    openShipment: (...a: unknown[]) => openShipment(...a),
    packShipment: (...a: unknown[]) => packShipment(...a),
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
  FreeScanField: ({ onScan }: { onScan: (c: string) => void }) => <button onClick={() => onScan('UL-9')}>scan</button>,
}))

import { PackScreen } from '@/screens/pack-screen'

describe('PackScreen', () => {
  beforeEach(() => {
    getUnitLoadByLabel.mockReset()
    findPickOrderByTargetUl.mockReset()
    openShipment.mockReset()
    packShipment.mockReset()
  })

  it('scans a picked UL, packs it into one shipping unit', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'UL-9', storageLocationName: 'PACK-1', unitLoadTypeName: 'EURO', stockUnits: [] })
    findPickOrderByTargetUl.mockResolvedValue({ id: 40, pickOrderNumber: 'PO-40', deliveryOrderId: 5, state: 600 })
    openShipment.mockResolvedValue({ id: 70 })
    packShipment.mockResolvedValue(undefined)
    render(<MemoryRouter><PackScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText(/PO-40/))
    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '2.5' } })
    screen.getByRole('button', { name: 'PACK' }).click()
    await waitFor(() => expect(packShipment).toHaveBeenCalledWith(70, 2.5, 'CARTON'))
    await waitFor(() => screen.getByText(/packed/i))
  })

  it('shows an error panel when no picked order targets the scanned UL', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'UL-9', storageLocationName: 'PACK-1', unitLoadTypeName: 'EURO', stockUnits: [] })
    // findPickOrderByTargetUl throws a plain Error (not ApiError) when no PICKED order targets
    // this UL -- matches the real menuApi implementation. The operator must see THIS message,
    // not a generic "unit load not found" fallback (the UL scan itself succeeded).
    findPickOrderByTargetUl.mockRejectedValue(new Error('No picked order for this unit load'))
    render(<MemoryRouter><PackScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('No picked order for this unit load'))
  })

  it('shows an error panel when packing itself fails', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'UL-9', storageLocationName: 'PACK-1', unitLoadTypeName: 'EURO', stockUnits: [] })
    findPickOrderByTargetUl.mockResolvedValue({ id: 40, pickOrderNumber: 'PO-40', deliveryOrderId: 5, state: 600 })
    const { ApiError } = await import('@/lib/api-client')
    openShipment.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'Pack failed: no shipment' }))
    render(<MemoryRouter><PackScreen /></MemoryRouter>)
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText(/PO-40/))
    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '2.5' } })
    screen.getByRole('button', { name: 'PACK' }).click()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Pack failed: no shipment'))
  })

  it('renders a back to menu button', () => {
    render(<MemoryRouter><PackScreen /></MemoryRouter>)
    expect(screen.getByText('Back to menu')).toBeInTheDocument()
  })
})
