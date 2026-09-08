import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import { MoveExecution } from '@/screens/move-execution'

vi.mock('@/lib/api-client', () => {
  class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.name = 'ApiError'; this.problem = problem }
  }
  return { ApiError, toast: { error: vi.fn() } }
})

vi.mock('@/lib/work-api', () => ({
  refId: (r: string) => Number(r.split(':')[1]),
  workApi: {
    getTransportOrder: vi.fn().mockResolvedValue({ id: 5, orderNumber: 'TO-5', transportType: 'PUTAWAY', unitLoadLabel: 'UL-7782',
      sourceLocationName: 'A-01-02', destinationLocationName: 'R-03-11', suggestedLocationName: null, state: 400, pausedAt: null }),
    startTransport: vi.fn().mockResolvedValue({}),
    completeTransport: vi.fn().mockResolvedValue({}),
    release: vi.fn(),
  },
}))

function renderMove() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/move/PUTAWAY:5']}>
        <Routes><Route path="/move/:ref" element={<MoveExecution />} /><Route path="/" element={<div>INBOX</div>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('scan UL -> scan destination -> COMPLETE -> done -> inbox', async () => {
  const { workApi } = await import('@/lib/work-api')
  renderMove()
  await userEvent.type(await screen.findByLabelText('Scan unit-load'), 'UL-7782{Enter}')
  await userEvent.type(await screen.findByLabelText('Scan location'), 'R-03-11{Enter}')
  await userEvent.click(await screen.findByText('COMPLETE'))
  await userEvent.click(await screen.findByText('BACK TO INBOX'))
  expect(await screen.findByText('INBOX')).toBeInTheDocument()
  expect(workApi.startTransport).toHaveBeenCalledWith(5)
  expect(workApi.completeTransport).toHaveBeenCalledWith(5)
})

test('wrong unit-load scan shows error and stays on source', async () => {
  renderMove()
  await userEvent.type(await screen.findByLabelText('Scan unit-load'), 'UL-0000{Enter}')
  expect(await screen.findByText(/Wrong unit-load/)).toBeInTheDocument()
  expect(screen.getByLabelText('Scan unit-load')).toBeInTheDocument()
})

test('startTransport 409 (already started) is swallowed and completeTransport still called', async () => {
  const { workApi } = await import('@/lib/work-api')
  const { ApiError: MockApiError } = await import('@/lib/api-client')
  vi.mocked(workApi.startTransport).mockRejectedValueOnce(new MockApiError({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'already started' }))
  renderMove()
  await userEvent.type(await screen.findByLabelText('Scan unit-load'), 'UL-7782{Enter}')
  await userEvent.type(await screen.findByLabelText('Scan location'), 'R-03-11{Enter}')
  await userEvent.click(await screen.findByText('COMPLETE'))
  expect(await screen.findByText('Move complete')).toBeInTheDocument()
  expect(workApi.completeTransport).toHaveBeenCalledWith(5)
})

test('a paused move hides the scan flow and COMPLETE, keeps Release', async () => {
  const { workApi } = await import('@/lib/work-api')
  vi.mocked(workApi.getTransportOrder).mockResolvedValueOnce({
    id: 5, orderNumber: 'TO-5', transportType: 'PUTAWAY', unitLoadLabel: 'UL-7782',
    sourceLocationName: 'A-01-02', destinationLocationName: 'R-03-11', suggestedLocationName: null,
    state: 400, pausedAt: '2026-08-01T00:00:00Z',
  } as never)
  renderMove()
  expect(await screen.findByText('Task is paused — resume it from the desktop board')).toBeInTheDocument()
  expect(screen.queryByLabelText('Scan unit-load')).not.toBeInTheDocument()
  expect(screen.queryByText('COMPLETE')).not.toBeInTheDocument()
  expect(await screen.findByText('Release task')).toBeInTheDocument()
})
