import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import { InboxHome } from '@/screens/inbox-home'

const logoutSpy = vi.fn()
vi.mock('@/auth/auth-provider', () => ({ useAuth: () => ({ userName: 'alice', logout: logoutSpy }) }))
vi.mock('@/lib/work-api', () => ({
  workApi: { mine: vi.fn().mockResolvedValue([]), next: vi.fn() },
  refId: (r: string) => Number(r.split(':')[1]),
  routeFor: (workType: string, ref: string) => {
    const seg = workType === 'PICK' ? 'pick' : workType === 'COUNT' ? 'count' : 'move'
    return `/${seg}/${encodeURIComponent(ref)}`
  },
}))
vi.mock('@/lib/offline/op-queue', () => ({ listFailed: vi.fn().mockResolvedValue([]) }))
import { workApi } from '@/lib/work-api'
import { listFailed } from '@/lib/offline/op-queue'
import { notifyChange } from '@/lib/offline/sync'

function renderHome() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<InboxHome />} />
          <Route path="/pick/:ref" element={<div>PICK SCREEN</div>} />
          <Route path="/count/:ref" element={<div>COUNT SCREEN</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('Get Next claims and navigates to the pick screen', async () => {
  ;(workApi.next as ReturnType<typeof vi.fn>).mockResolvedValue({ ref: 'PICK:42', workType: 'PICK', summary: 'Pick PO-42' })
  renderHome()
  await userEvent.click(await screen.findByText('GET NEXT TASK'))
  expect(await screen.findByText('PICK SCREEN')).toBeInTheDocument()
})

test('Get Next with COUNT work-type navigates to count screen', async () => {
  ;(workApi.next as ReturnType<typeof vi.fn>).mockResolvedValue({ ref: 'COUNT:7', workType: 'COUNT', summary: 'Count R-03' })
  renderHome()
  await userEvent.click(await screen.findByText('GET NEXT TASK'))
  expect(await screen.findByText('COUNT SCREEN')).toBeInTheDocument()
})

test('Get Next with empty pool shows no-work message', async () => {
  ;(workApi.next as ReturnType<typeof vi.fn>).mockResolvedValue(null)
  renderHome()
  await userEvent.click(await screen.findByText('GET NEXT TASK'))
  await waitFor(() => expect(screen.getByText('No work available right now.')).toBeInTheDocument())
})

test('Sign out button calls logout', async () => {
  logoutSpy.mockClear()
  renderHome()
  await userEvent.click(screen.getByRole('button', { name: /sign out/i }))
  expect(logoutSpy).toHaveBeenCalled()
})

function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }
afterEach(() => setOnline(true))

test('GET NEXT TASK is disabled when offline', async () => {
  setOnline(false)
  renderHome()
  expect((await screen.findByText('GET NEXT TASK')).closest('button')).toBeDisabled()
})

test('failed-op count re-renders after a mid-session drain failure (onSyncChange subscription)', async () => {
  ;(listFailed as ReturnType<typeof vi.fn>).mockResolvedValue([])
  renderHome()
  await screen.findByText('GET NEXT TASK')
  expect(screen.queryByText(/failed to sync/i)).not.toBeInTheDocument()

  ;(listFailed as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: '1' }])
  notifyChange()
  await waitFor(() => expect(screen.getByText(/1 action failed to sync/i)).toBeInTheDocument())
})
