import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import { PickExecution } from '@/screens/pick-execution'
import { workApi } from '@/lib/work-api'

vi.mock('@/lib/work-api', () => ({
  refId: (r: string) => Number(r.split(':')[1]),
  workApi: {
    getPickOrder: vi.fn().mockResolvedValue({ id: 1, pickOrderNumber: 'PO-1', deliveryOrderNumber: 'DO-1', state: 100, bulk: false, picks: [
      { id: 11, itemDataNumber: 'SKU-A', sourceStockUnitId: 99, plannedAmount: 5, pickedAmount: 0, state: 100 },
    ]}),
    confirmPick: vi.fn().mockResolvedValue({}),
    release: vi.fn(),
    getStockUnit: vi.fn().mockResolvedValue({ id: 99, locationName: 'A-01', itemDataNumber: 'SKU-A' }),
    getBulkLines: vi.fn().mockResolvedValue([]),
    bulkConfirm: vi.fn().mockResolvedValue({ filledSlices: 0, shortSlices: 0 }),
  },
}))

function renderPick() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/pick/PICK:1']}>
        <Routes><Route path="/pick/:ref" element={<PickExecution />} /><Route path="/" element={<div>INBOX</div>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('happy path: scan location, scan item, confirm -> complete -> back to inbox', async () => {
  renderPick()
  await userEvent.type(await screen.findByLabelText('Scan location'), 'A-01{Enter}')
  await userEvent.type(await screen.findByLabelText('Scan item'), 'SKU-A{Enter}')
  await userEvent.click(await screen.findByText('CONFIRM'))
  await userEvent.click(await screen.findByText('BACK TO INBOX'))
  expect(await screen.findByText('INBOX')).toBeInTheDocument()
})

test('wrong location scan shows error and stays on location step', async () => {
  renderPick()
  await userEvent.type(await screen.findByLabelText('Scan location'), 'Z-99{Enter}')
  expect(await screen.findByText(/Wrong location/)).toBeInTheDocument()
  expect(screen.getByLabelText('Scan location')).toBeInTheDocument()
})

test('bulk pick order renders the BULK PICK runner', async () => {
  vi.mocked(workApi.getPickOrder).mockResolvedValueOnce({
    id: 1, pickOrderNumber: 'PO-1', deliveryOrderNumber: null, state: 100, bulk: true, picks: [],
  })
  vi.mocked(workApi.getBulkLines).mockResolvedValueOnce([
    { sourceStockUnitId: 3, locationName: 'A-01-01', unitLoadLabel: 'UL-1', itemDataNumber: 'SKU-1', lotNumber: null, plannedTotal: 40, pickedTotal: 0, openSlices: 3 },
  ])
  renderPick()
  expect(await screen.findByText('BULK PICK')).toBeInTheDocument()
})
