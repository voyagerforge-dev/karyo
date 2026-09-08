import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import { ReceiveExecution } from '@/screens/receive-execution'
import { ApiError } from '@/lib/api-client'

function problem(type: string) {
  return new ApiError({ type: `https://karyo.com/errors/${type}`, title: type, status: 409, detail: type })
}

function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }
afterEach(() => setOnline(true))
beforeEach(() => { receiveLine.mockClear() })

const { entry, receiveLine } = vi.hoisted(() => ({
  entry: {
    id: 1, receiptNumber: 'GR-1', state: 500, dockLocationId: 9, dockLocationName: 'DOCK-1', pausedAt: null, receivedCount: 0,
    lines: [
      { asnLineId: 11, asnNumber: 'ASN-1', itemDataNumber: 'SKU-A', remainingAmount: 5 },
      { asnLineId: 12, asnNumber: 'ASN-1', itemDataNumber: 'SKU-B', remainingAmount: 3 },
    ],
  },
  receiveLine: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/lib/work-api', () => ({
  refId: (r: string) => Number(r.split(':')[1]),
  workApi: { getReceiveEntry: vi.fn().mockResolvedValue(entry), receiveLine, release: vi.fn() },
}))

function renderReceive() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/receive/RECEIVE:1']}>
        <Routes><Route path="/receive/:ref" element={<ReceiveExecution />} /><Route path="/" element={<div>INBOX</div>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('select a line, qty defaults to remaining, review, confirm -> next line -> done -> inbox', async () => {
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  expect(screen.getByLabelText('quantity')).toHaveValue(5) // default = remaining
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(receiveLine).toHaveBeenCalledWith(1, {
    asnLineId: 11, amount: 5, locationId: 9, locationName: 'DOCK-1', unitLoadLabel: undefined, allowOverReceipt: false,
  })

  // second (last) line
  await screen.findByText('SKU-B')
  await userEvent.click(screen.getByText('SKU-B'))
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(receiveLine).toHaveBeenCalledWith(1, expect.objectContaining({ asnLineId: 12, amount: 3 }))

  await userEvent.click(await screen.findByText('BACK TO INBOX'))
  expect(await screen.findByText('INBOX')).toBeInTheDocument()
})

test('scanning an optional unit-load label fills it and is sent on confirm', async () => {
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  await userEvent.type(screen.getByLabelText('Scan unit-load label (optional)'), 'UL-9001{Enter}')
  expect(await screen.findByText('Label: UL-9001')).toBeInTheDocument()
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(receiveLine).toHaveBeenCalledWith(1, expect.objectContaining({ unitLoadLabel: 'UL-9001' }))
})

test('skip-line advances without calling receiveLine', async () => {
  renderReceive()
  await userEvent.click(await screen.findByLabelText('Skip SKU-A'))
  expect(receiveLine).not.toHaveBeenCalled()
  await screen.findByText('SKU-B')
  expect(screen.queryByText('SKU-A')).not.toBeInTheDocument()
})

test('finish receiving early reaches done with lines still open', async () => {
  renderReceive()
  await userEvent.click(await screen.findByText('Finish receiving'))
  expect(await screen.findByText('Receiving complete')).toBeInTheDocument()
  expect(receiveLine).not.toHaveBeenCalled()
})

test('no dock location disables entry with an honest banner', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getReceiveEntry).mockResolvedValueOnce({ ...entry, dockLocationId: null, dockLocationName: null } as never)
  renderReceive()
  expect(await screen.findByText(/no dock location/)).toBeInTheDocument()
  expect(screen.queryByText('SKU-A')).not.toBeInTheDocument()
})

test('a paused receipt disables entry', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getReceiveEntry).mockResolvedValueOnce({ ...entry, pausedAt: '2026-08-01T00:00:00Z' } as never)
  renderReceive()
  expect(await screen.findByText('Receipt is paused')).toBeInTheDocument()
  expect(screen.queryByText('SKU-A')).not.toBeInTheDocument()
})

test('over-receipt (409) shows a typed inline error and stays on the confirm step', async () => {
  receiveLine.mockRejectedValueOnce(problem('over-receipt'))
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(await screen.findByText('Received more than the ASN allows for this line')).toBeInTheDocument()
  expect(screen.getByText('CONFIRM RECEIVE')).toBeInTheDocument()
})

test('receipt-paused (409) re-renders into the existing paused dead-end', async () => {
  receiveLine.mockRejectedValueOnce(problem('receipt-paused'))
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(await screen.findByText('Receipt is paused')).toBeInTheDocument()
  expect(screen.queryByText('CONFIRM RECEIVE')).not.toBeInTheDocument()
})

test('receipt-not-receivable (409) shows a typed inline error', async () => {
  receiveLine.mockRejectedValueOnce(problem('receipt-not-receivable'))
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(await screen.findByText('This receipt is closed')).toBeInTheDocument()
})

test('an untyped failure keeps the generic retry copy', async () => {
  receiveLine.mockRejectedValueOnce(new Error('boom'))
  renderReceive()
  await userEvent.click(await screen.findByText('SKU-A'))
  await userEvent.click(screen.getByText('REVIEW'))
  await userEvent.click(screen.getByText('CONFIRM RECEIVE'))
  expect(await screen.findByText(/Could not receive this line/)).toBeInTheDocument()
})

test('offline disables entry -- no queueing, connection-required banner', async () => {
  setOnline(false)
  renderReceive()
  expect(await screen.findByText('Receiving requires a connection')).toBeInTheDocument()
  expect(screen.queryByText('SKU-A')).not.toBeInTheDocument()
})
