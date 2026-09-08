import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'

const getLocationByCode = vi.fn()
const startAdhocCount = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: {
    getLocationByCode: (...a: unknown[]) => getLocationByCode(...a),
    startAdhocCount: (...a: unknown[]) => startAdhocCount(...a),
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

import { AdhocCountScreen } from '@/screens/adhoc-count-screen'

function renderScreen() {
  return render(
    <MemoryRouter initialEntries={['/adhoc-count']}>
      <Routes>
        <Route path="/adhoc-count" element={<AdhocCountScreen />} />
        <Route path="/count/:ref" element={<p>count-execution</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdhocCountScreen', () => {
  beforeEach(() => {
    getLocationByCode.mockReset()
    startAdhocCount.mockReset()
  })

  it('scans a location, starts a count, hands off to count execution', async () => {
    scans = ['A-01-01']
    getLocationByCode.mockResolvedValue({ id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' })
    startAdhocCount.mockResolvedValue(88)
    renderScreen()
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText(/A-01-01/))
    screen.getByText('START COUNT').click()
    await waitFor(() => expect(startAdhocCount).toHaveBeenCalledWith(3))
    // routeFor('COUNT', 'COUNT:88') derives the count-order id via refId() -> ref.split(':')[1]
    await waitFor(() => screen.getByText('count-execution'))
  })

  it('reports an error when the scanned location does not resolve', async () => {
    scans = ['GHOST']
    const { ApiError } = await import('@/lib/api-client')
    const { toast } = await import('@/lib/api-client')
    getLocationByCode.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Not Found', status: 404, detail: 'not found' }))
    renderScreen()
    screen.getByText('scan').click()
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Location "GHOST" not found'))
  })

  it('reports an error when starting the count fails', async () => {
    scans = ['A-01-01']
    getLocationByCode.mockResolvedValue({ id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' })
    const { ApiError, toast } = await import('@/lib/api-client')
    startAdhocCount.mockRejectedValue(new ApiError({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'Location busy' }))
    renderScreen()
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText(/A-01-01/))
    screen.getByText('START COUNT').click()
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Location busy'))
  })

  it('lets the operator pick a different location before starting', async () => {
    scans = ['A-01-01']
    getLocationByCode.mockResolvedValue({ id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' })
    renderScreen()
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText(/A-01-01/))
    screen.getByText('Different location').click()
    await waitFor(() => screen.getByText('scan'))
    expect(startAdhocCount).not.toHaveBeenCalled()
  })

  it('renders a back to menu button', () => {
    renderScreen()
    expect(screen.getByText('Back to menu')).toBeInTheDocument()
  })
})
