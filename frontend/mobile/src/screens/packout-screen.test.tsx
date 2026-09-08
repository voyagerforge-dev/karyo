import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const listReadyGroups = vi.fn()
const openPackout = vi.fn()
const packoutContainer = vi.fn()
const packoutAddLine = vi.fn()
const packoutClose = vi.fn()
const packoutComplete = vi.fn()
const printLabel = vi.fn()
const getUnitLoadByLabel = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    listReadyGroups: (...a: unknown[]) => listReadyGroups(...a),
    openPackout: (...a: unknown[]) => openPackout(...a),
    packoutContainer: (...a: unknown[]) => packoutContainer(...a),
    packoutAddLine: (...a: unknown[]) => packoutAddLine(...a),
    packoutClose: (...a: unknown[]) => packoutClose(...a),
    packoutComplete: (...a: unknown[]) => packoutComplete(...a),
    printLabel: (...a: unknown[]) => printLabel(...a),
    getUnitLoadByLabel: (...a: unknown[]) => getUnitLoadByLabel(...a),
  },
}))
const toastError = vi.fn()
vi.mock('@/lib/api-client', () => ({
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { success: vi.fn(), error: (...a: unknown[]) => toastError(...a) },
}))
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan, label }: { onScan: (c: string) => void; label: string }) => (
    <button onClick={() => onScan(label.toLowerCase().includes('item') ? 'SKU-1' : 'LPN-1')}>{label}</button>
  ),
}))

import { PackoutScreen } from '@/screens/packout-screen'

const READY = [{ waveId: 9, waveNumber: 'W-9', groupId: 1, sortSlot: '01', destinationKey: 'ACME|Main St|1|10001|NYC|US' }]

const item = (over: Partial<{ sorted: number; packed: number; remaining: number; lotNumber: string | null }> = {}) => ({
  itemDataId: 5, itemDataNumber: 'SKU-1', lotNumber: null, sorted: 25, packed: 0, remaining: 25, ...over,
})
const openContainer = (lines: unknown[] = []) => ({
  id: 77, shippingUnitNumber: 'SU-1', unitLoadId: 501, state: 'OPEN', type: 'CARTON', weight: 0, lines,
})
const base = {
  shipmentId: 40, shipmentNumber: 'SH-40', shipmentState: 100, groupId: 1, sortSlot: '01',
  destinationKey: 'ACME|Main St|1|10001|NYC|US', memberOrderIds: [11, 12],
  items: [item()], containers: [] as unknown[], complete: false,
}
const withContainer = { ...base, containers: [openContainer()] }
const withLine = {
  ...base,
  items: [item({ packed: 25, remaining: 0 })],
  containers: [openContainer([{ deliveryOrderId: 11, itemDataNumber: 'SKU-1', lotNumber: null, amount: 25 }])],
}
const closed = {
  ...withLine,
  containers: [{ ...openContainer([{ deliveryOrderId: 11, itemDataNumber: 'SKU-1', lotNumber: null, amount: 25 }]), state: 'CLOSED', weight: 2.5 }],
}

describe('PackoutScreen', () => {
  beforeEach(() => {
    listReadyGroups.mockReset(); openPackout.mockReset(); packoutContainer.mockReset()
    packoutAddLine.mockReset(); packoutClose.mockReset(); packoutComplete.mockReset()
    printLabel.mockReset(); getUnitLoadByLabel.mockReset(); toastError.mockReset()
    printLabel.mockResolvedValue(undefined)
  })

  it('picks a READY group, packs a carton, closes and prints it, then completes the group', async () => {
    listReadyGroups.mockResolvedValue(READY)
    openPackout.mockResolvedValue(base)
    packoutContainer.mockResolvedValue(withContainer)
    packoutAddLine.mockResolvedValue(withLine)
    packoutClose.mockResolvedValue(closed)
    packoutComplete.mockResolvedValue({ ...closed, complete: true })

    render(<MemoryRouter><PackoutScreen /></MemoryRouter>)

    fireEvent.click(await screen.findByRole('button', { name: /ACME/ }))
    await waitFor(() => expect(openPackout).toHaveBeenCalledWith(9, 1))
    expect(screen.getByTestId('packout-slot')).toHaveTextContent('01')
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByTestId('packout-remaining')).toHaveTextContent('25')
    // Nothing packed yet: the server would 409 wave-packout-conflict, so the button is dead.
    expect(screen.getByRole('button', { name: 'COMPLETE' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'NEW CARTON' }))
    await waitFor(() => expect(packoutContainer).toHaveBeenCalledWith(9, 1, { type: 'CARTON' }))

    fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '25' } })
    fireEvent.click(screen.getByRole('button', { name: 'Scan item' }))
    await waitFor(() =>
      expect(packoutAddLine).toHaveBeenCalledWith(9, 1, 77, { itemDataNumber: 'SKU-1', lotNumber: undefined, amount: 25 }),
    )
    await waitFor(() => expect(screen.getByTestId('packout-remaining')).toHaveTextContent('0'))
    // Remaining is 0 but the carton is still open -- the server refuses that too.
    expect(screen.getByRole('button', { name: 'COMPLETE' })).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '2.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'CLOSE' }))
    await waitFor(() => expect(packoutClose).toHaveBeenCalledWith(9, 1, 77, 2.5))
    await waitFor(() => expect(printLabel).toHaveBeenCalledWith(501))

    const complete = await screen.findByRole('button', { name: 'COMPLETE' })
    expect(complete).toBeEnabled()
    fireEvent.click(complete)
    await waitFor(() => screen.getByText(/Group packed/))
  })

  it('asks which lot only when the group holds more than one lot of the scanned SKU', async () => {
    const twoLots = {
      ...base,
      items: [
        item({ lotNumber: 'LOT-A', sorted: 10, remaining: 10 }),
        { ...item({ lotNumber: 'LOT-B', sorted: 15, remaining: 15 }), itemDataId: 6 },
      ],
    }
    listReadyGroups.mockResolvedValue(READY)
    openPackout.mockResolvedValue(twoLots)
    packoutContainer.mockResolvedValue({ ...twoLots, containers: [openContainer()] })
    packoutAddLine.mockResolvedValue({ ...twoLots, containers: [openContainer()] })

    render(<MemoryRouter><PackoutScreen /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /ACME/ }))
    await waitFor(() => expect(openPackout).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'NEW CARTON' }))
    fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Scan item' }))

    // No line posted yet: the screen has to know which lot first.
    await waitFor(() => screen.getByText(/Which lot/i))
    expect(packoutAddLine).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'LOT-B' }))
    await waitFor(() =>
      expect(packoutAddLine).toHaveBeenCalledWith(9, 1, 77, { itemDataNumber: 'SKU-1', lotNumber: 'LOT-B', amount: 4 }),
    )
  })

  it('a print failure is toasted but never blocks the close', async () => {
    const { ApiError } = await import('@/lib/api-client')
    listReadyGroups.mockResolvedValue(READY)
    openPackout.mockResolvedValue(withContainer)
    packoutClose.mockResolvedValue(closed)
    printLabel.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'x', status: 503, detail: 'No printer configured' }))

    render(<MemoryRouter><PackoutScreen /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /ACME/ }))
    await waitFor(() => screen.getByLabelText('Weight (kg)'))
    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'CLOSE' }))
    await waitFor(() => expect(toastError).toHaveBeenCalledWith('No printer configured'))
    // The close itself stood: the carton is CLOSED and COMPLETE is live.
    expect(screen.getByRole('button', { name: 'COMPLETE' })).toBeEnabled()
  })

  it('adopts a scanned empty LPN as the container', async () => {
    listReadyGroups.mockResolvedValue(READY)
    openPackout.mockResolvedValue(base)
    getUnitLoadByLabel.mockResolvedValue({ id: 601, labelId: 'LPN-1', storageLocationName: 'PACK', unitLoadTypeName: 'CARTON', stockUnits: [] })
    packoutContainer.mockResolvedValue(withContainer)

    render(<MemoryRouter><PackoutScreen /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /ACME/ }))
    await waitFor(() => screen.getByRole('button', { name: 'Scan LPN' }))
    fireEvent.click(screen.getByRole('button', { name: 'Scan LPN' }))
    await waitFor(() => expect(getUnitLoadByLabel).toHaveBeenCalledWith('LPN-1'))
    await waitFor(() => expect(packoutContainer).toHaveBeenCalledWith(9, 1, { unitLoadId: 601, type: 'CARTON' }))
  })

  it('reports an empty ready list rather than an empty screen', async () => {
    listReadyGroups.mockResolvedValue([])
    render(<MemoryRouter><PackoutScreen /></MemoryRouter>)
    await waitFor(() => screen.getByText(/No groups ready/i))
  })
})
