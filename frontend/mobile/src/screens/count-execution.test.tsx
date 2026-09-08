import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import { CountExecution } from '@/screens/count-execution'

const { entry, submitCount, locationEmpty } = vi.hoisted(() => ({
  entry: { id: 1, orderNumber: 'CO-1', locationName: 'R-03-11', lines: [
    { lineId: 11, itemDataNumber: 'SKU-A' }, { lineId: 12, itemDataNumber: 'SKU-B' },
  ] },
  submitCount: vi.fn().mockResolvedValue({}),
  locationEmpty: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/lib/work-api', () => ({
  refId: (r: string) => Number(r.split(':')[1]),
  workApi: { getCountEntry: vi.fn().mockResolvedValue(entry), submitCount, locationEmpty, release: vi.fn() },
}))

function renderCount() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/count/COUNT:1']}>
        <Routes><Route path="/count/:ref" element={<CountExecution />} /><Route path="/" element={<div>INBOX</div>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('scan location, count two lines blind, submit -> done -> inbox', async () => {
  renderCount()
  await userEvent.type(await screen.findByLabelText('Scan location'), 'R-03-11{Enter}')
  // line 1 — clear the initial '0' before typing so qty = 7 (not 07, though Number('07')===7)
  await userEvent.type(await screen.findByLabelText('Scan item'), 'SKU-A{Enter}')
  const qty1 = screen.getByLabelText('quantity')
  await userEvent.clear(qty1)
  await userEvent.type(qty1, '7')
  await userEvent.click(screen.getByText('NEXT'))
  // line 2 (last)
  await userEvent.type(await screen.findByLabelText('Scan item'), 'SKU-B{Enter}')
  const qty2 = screen.getByLabelText('quantity')
  await userEvent.clear(qty2)
  await userEvent.type(qty2, '3')
  await userEvent.click(screen.getByText('SUBMIT'))
  await userEvent.click(await screen.findByText('BACK TO INBOX'))
  expect(await screen.findByText('INBOX')).toBeInTheDocument()
  // submitted both lines
  expect(submitCount).toHaveBeenCalledWith(1, expect.arrayContaining([
    { lineId: 11, countedAmount: 7 }, { lineId: 12, countedAmount: 3 },
  ]))
})

test('wrong item scan shows error and stays on item step', async () => {
  renderCount()
  await userEvent.type(await screen.findByLabelText('Scan location'), 'R-03-11{Enter}')
  await userEvent.type(await screen.findByLabelText('Scan item'), 'SKU-ZZZ{Enter}')
  expect(await screen.findByText(/Wrong item/)).toBeInTheDocument()
  expect(screen.getByLabelText('Scan item')).toBeInTheDocument()
})

// St4 (Important 3, stocktaking-block T5 review): a line already zeroed by the web app's
// "unit load missing" action carries `counted: true` on the entry view -- the floor wizard must
// skip it entirely, not prompt for a fresh count (submitCount now 422s a stale input against a
// non-PLANNED line).
const { entryWithCounted } = vi.hoisted(() => ({
  entryWithCounted: {
    id: 2, orderNumber: 'CO-2', locationName: 'R-03-12',
    lines: [
      { lineId: 21, itemDataNumber: 'SKU-X', counted: true },
      { lineId: 22, itemDataNumber: 'SKU-Y' },
    ],
  },
}))

test('a line already counted elsewhere is skipped -- wizard walks only the open line', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getCountEntry).mockResolvedValueOnce(entryWithCounted as never)

  renderCount()
  await userEvent.type(await screen.findByLabelText('Scan location'), 'R-03-12{Enter}')
  // only SKU-Y is prompted -- SKU-X (counted:true) never appears
  expect(await screen.findByText('SKU-Y')).toBeInTheDocument()
  expect(screen.queryByText('SKU-X')).not.toBeInTheDocument()
  // step header reflects one open line ("1 / 1"), not two
  expect(screen.getByText('1 / 1')).toBeInTheDocument()

  await userEvent.type(await screen.findByLabelText('Scan item'), 'SKU-Y{Enter}')
  const qty = screen.getByLabelText('quantity')
  await userEvent.clear(qty)
  await userEvent.type(qty, '5')
  await userEvent.click(screen.getByText('SUBMIT'))
  await screen.findByText('BACK TO INBOX')

  // the already-counted line is never re-submitted
  expect(submitCount).toHaveBeenCalledWith(2, [{ lineId: 22, countedAmount: 5 }])
})

test('every line already counted -- submits empty and finishes without prompting', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getCountEntry).mockResolvedValueOnce({
    id: 3, orderNumber: 'CO-3', locationName: 'R-03-13',
    lines: [{ lineId: 31, itemDataNumber: 'SKU-Z', counted: true }],
  } as never)

  renderCount()
  expect(await screen.findByText('Every item here was already counted')).toBeInTheDocument()
  await userEvent.click(screen.getByText('SUBMIT'))
  await screen.findByText('BACK TO INBOX')

  expect(submitCount).toHaveBeenCalledWith(3, [])
})

// C1 (Critical, stocktaking-block final-gate review): a ZERO-LINE count order (nothing was on
// record at the location -- every END_OF_PERIOD order for an empty slot is one) has no terminal
// path through submitCount: the backend 422s an empty submit against an order that never had a
// line, so the operator loops forever and the location stays frozen. The only terminal op is
// POST .../location-empty.
test('a zero-line order offers LOCATION EMPTY and calls locationEmpty, never submitCount', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getCountEntry).mockResolvedValueOnce({
    id: 4, orderNumber: 'CO-4', locationName: 'R-03-14', lines: [],
  } as never)

  renderCount()
  expect(await screen.findByText(/Nothing on record here/)).toBeInTheDocument()
  // the all-lines-counted SUBMIT affordance must NOT be offered for a zero-line order
  expect(screen.queryByText('Every item here was already counted')).not.toBeInTheDocument()
  expect(screen.queryByText('SUBMIT')).not.toBeInTheDocument()

  await userEvent.click(screen.getByText('LOCATION EMPTY'))
  await screen.findByText('BACK TO INBOX')

  expect(locationEmpty).toHaveBeenCalledWith(4)
  expect(submitCount).not.toHaveBeenCalledWith(4, expect.anything())
})
