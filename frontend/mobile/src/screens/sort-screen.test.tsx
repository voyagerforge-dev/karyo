import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const getUnitLoadByLabel = vi.fn()
const resolveSortCart = vi.fn()
const sortScan = vi.fn()
const sortUndo = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    getUnitLoadByLabel: (...a: unknown[]) => getUnitLoadByLabel(...a),
    resolveSortCart: (...a: unknown[]) => resolveSortCart(...a),
    sortScan: (...a: unknown[]) => sortScan(...a),
    sortUndo: (...a: unknown[]) => sortUndo(...a),
  },
}))
vi.mock('@/lib/api-client', () => ({
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { success: vi.fn(), error: vi.fn() },
}))
let scanHandlers: Array<(c: string) => void> = []
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan, label }: { onScan: (c: string) => void; label: string }) => {
    scanHandlers.push(onScan)
    return <button onClick={() => onScan(label.includes('cart') ? 'CART-1' : 'SKU-1')}>{label}</button>
  },
}))

import { SortScreen } from '@/screens/sort-screen'

const cart = {
  waveId: 9, waveNumber: 'W-9', pickOrderId: 3, pickOrderNumber: 'WB-9-1', unitLoadId: 12,
  groups: [
    { groupId: 1, sortSlot: '01', destinationKey: 'A|||1|NYC|', state: 'IN_PROGRESS', picked: 20, sorted: 0, remaining: 20 },
    { groupId: 2, sortSlot: '02', destinationKey: 'B|||2|DC|', state: 'IN_PROGRESS', picked: 15, sorted: 0, remaining: 15 },
  ],
  items: [{ itemDataId: 5, itemDataNumber: 'SKU-1', lotNumber: null, picked: 35, sorted: 0, remaining: 35 }],
}

describe('SortScreen', () => {
  beforeEach(() => { scanHandlers = []; getUnitLoadByLabel.mockReset(); resolveSortCart.mockReset(); sortScan.mockReset(); sortUndo.mockReset() })

  it('scans cart, scans item, shows slot, undo last -- remaining always comes from a re-fetched cart', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'CART-1', storageLocationName: 'PACK', unitLoadTypeName: 'EURO', stockUnits: [] })
    resolveSortCart.mockResolvedValueOnce(cart) // initial scan-cart fetch: remaining 35
    resolveSortCart.mockResolvedValueOnce({ ...cart, items: [{ ...cart.items[0], sorted: 1, remaining: 34 }] }) // after scan
    resolveSortCart.mockResolvedValueOnce(cart) // after undo: back to remaining 35
    sortScan.mockResolvedValue({ scanId: 77, groupId: 1, sortSlot: '01', destinationKey: 'A|||1|NYC|', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 34 })
    sortUndo.mockResolvedValue({ scanId: 77, groupId: 1, sortSlot: '01', destinationKey: 'A|||1|NYC|', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 35 })
    render(<MemoryRouter><SortScreen /></MemoryRouter>)
    screen.getByText('Scan cart').click()
    await waitFor(() => screen.getByText(/W-9/))
    expect(screen.getByTestId('sort-remaining')).toHaveTextContent('35')
    screen.getByText('Scan item').click()
    await waitFor(() => expect(sortScan).toHaveBeenCalledWith(9, { cartUnitLoadId: 12, itemDataNumber: 'SKU-1', lotNumber: undefined, amount: 1 }))
    await waitFor(() => expect(screen.getByTestId('sort-remaining')).toHaveTextContent('34'))
    expect(screen.getByTestId('sort-slot')).toHaveTextContent('01')
    screen.getByRole('button', { name: 'UNDO LAST' }).click()
    await waitFor(() => expect(sortUndo).toHaveBeenCalledWith(9, 77))
    await waitFor(() => expect(screen.getByTestId('sort-remaining')).toHaveTextContent('35'))
    expect(resolveSortCart).toHaveBeenCalledTimes(3)
    expect(resolveSortCart).toHaveBeenNthCalledWith(1, 12)
    expect(resolveSortCart).toHaveBeenNthCalledWith(2, 12)
    expect(resolveSortCart).toHaveBeenNthCalledWith(3, 12)
  })

  it('prompts for a lot on 422 lot-required and resolves it via CONFIRM LOT, re-fetching the cart', async () => {
    const { ApiError } = await import('@/lib/api-client')
    const lotCart = {
      ...cart,
      items: [
        { itemDataId: 5, itemDataNumber: 'SKU-1', lotNumber: 'LOT-A', picked: 20, sorted: 0, remaining: 20 },
        { itemDataId: 6, itemDataNumber: 'SKU-1', lotNumber: 'LOT-B', picked: 15, sorted: 0, remaining: 15 },
      ],
    }
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'CART-1', storageLocationName: 'PACK', unitLoadTypeName: 'EURO', stockUnits: [] })
    resolveSortCart.mockResolvedValueOnce(lotCart) // initial: remaining 35
    resolveSortCart.mockResolvedValueOnce({ ...lotCart, items: [{ ...lotCart.items[0], sorted: 1, remaining: 19 }, lotCart.items[1]] }) // after CONFIRM LOT: remaining 34
    sortScan.mockRejectedValueOnce(new ApiError({ type: 'https://karyo.com/errors/wave-lot-required', title: 'x', status: 422, detail: 'Lot required' }))
    sortScan.mockResolvedValueOnce({ scanId: 88, groupId: 1, sortSlot: '01', destinationKey: 'A|||1|NYC|', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 19 })
    render(<MemoryRouter><SortScreen /></MemoryRouter>)
    screen.getByText('Scan cart').click()
    await waitFor(() => screen.getByText(/W-9/))
    screen.getByText('Scan item').click()
    await waitFor(() => screen.getByLabelText('Lot'))
    fireEvent.change(screen.getByLabelText('Lot'), { target: { value: 'LOT-A' } })
    screen.getByRole('button', { name: 'CONFIRM LOT' }).click()
    await waitFor(() => expect(sortScan).toHaveBeenCalledWith(9, { cartUnitLoadId: 12, itemDataNumber: 'SKU-1', lotNumber: 'LOT-A', amount: 1 }))
    await waitFor(() => expect(screen.getByTestId('sort-remaining')).toHaveTextContent('34'))
  })

  it('ignores a second item scan while the first is still in flight', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'CART-1', storageLocationName: 'PACK', unitLoadTypeName: 'EURO', stockUnits: [] })
    resolveSortCart.mockResolvedValue(cart)
    // The first scan stays in flight until this test releases it, so the two scans below land
    // while it is still pending -- the double-trigger a wedge or camera can produce.
    let release: (v: unknown) => void = () => {}
    sortScan.mockReturnValueOnce(new Promise((resolve) => { release = resolve }))
    render(<MemoryRouter><SortScreen /></MemoryRouter>)
    screen.getByText('Scan cart').click()
    await waitFor(() => screen.getByText(/W-9/))
    screen.getByText('Scan item').click()
    await waitFor(() => expect(sortScan).toHaveBeenCalledTimes(1))
    screen.getByText('Scan item').click()
    screen.getByText('Scan item').click()
    expect(sortScan).toHaveBeenCalledTimes(1)
    release({ scanId: 77, groupId: 1, sortSlot: '01', destinationKey: 'A|||1|NYC|', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 34 })
    await waitFor(() => expect(screen.getByTestId('sort-slot')).toHaveTextContent('01'))
  })

  it('shows cart done when the re-fetched cart has zero remaining', async () => {
    getUnitLoadByLabel.mockResolvedValue({ id: 12, labelId: 'CART-1', storageLocationName: 'PACK', unitLoadTypeName: 'EURO', stockUnits: [] })
    resolveSortCart.mockResolvedValueOnce({ ...cart, items: [{ ...cart.items[0], picked: 1, remaining: 1 }] })
    resolveSortCart.mockResolvedValueOnce({ ...cart, items: [{ ...cart.items[0], picked: 1, sorted: 1, remaining: 0 }] })
    sortScan.mockResolvedValue({ scanId: 1, groupId: 1, sortSlot: '01', destinationKey: 'A', groupState: 'READY', amount: 1, cartRemainingForItem: 0 })
    render(<MemoryRouter><SortScreen /></MemoryRouter>)
    screen.getByText('Scan cart').click()
    await waitFor(() => screen.getByText(/W-9/))
    screen.getByText('Scan item').click()
    await waitFor(() => screen.getByText(/cart done/i))
  })
})
