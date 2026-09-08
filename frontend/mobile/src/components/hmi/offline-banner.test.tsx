import { render, screen, waitFor } from '@testing-library/react'
import { OfflineBanner } from '@/components/hmi/offline-banner'
import { enqueue } from '@/lib/offline/op-queue'
import { workApi } from '@/lib/work-api'
import { router } from '@/routes/router'

function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }
beforeEach(async () => {
  await new Promise((r) => { const req = indexedDB.deleteDatabase('karyo-offline'); req.onsuccess = req.onerror = () => r(null) })
})
afterEach(async () => { setOnline(true); await router.navigate('/') })

test('shows the offline message when offline', () => {
  setOnline(false)
  render(<OfflineBanner />)
  expect(screen.getByText(/offline/i)).toBeInTheDocument()
})

test('shows a pending count when ops are queued', async () => {
  setOnline(false)
  await enqueue({ id: '1', type: 'pick-confirm', method: 'POST', url: '/x', label: 'c', createdAt: 1, status: 'pending' })
  render(<OfflineBanner />)
  await waitFor(() => expect(screen.getByText(/1 pending/i)).toBeInTheDocument())
})

test('renders nothing when online with an empty queue', () => {
  setOnline(true)
  const { container } = render(<OfflineBanner />)
  expect(container).toBeEmptyDOMElement()
})

test('pending count updates when an op is enqueued after the banner is already mounted', async () => {
  // The banner is rendered first with an empty queue, then an op arrives via queuedPost
  // (which calls notifyChange after enqueue). The banner must re-read countPending()
  // and show the new count without needing an online/offline event.
  setOnline(false)
  render(<OfflineBanner />)
  // Initially empty queue → "work will sync" (no count shown)
  expect(screen.getByText('Offline - work will sync')).toBeInTheDocument()

  // Enqueue via the real work-api offline path (triggers notifyChange → banner refresh)
  await workApi.confirmPick(99, 3)

  await waitFor(() => expect(screen.getByText(/1 pending/i)).toBeInTheDocument())
})

test('on a menu-transaction route, offline shows a connection-required message with no sync promise', async () => {
  await router.navigate('/menu')
  setOnline(false)
  render(<OfflineBanner />)
  await waitFor(() => expect(screen.getByText('OFFLINE - connection required')).toBeInTheDocument())
  expect(screen.queryByText(/sync/i)).not.toBeInTheDocument()
})

test('on a directed-work route, offline keeps the sync-promise wording (hyphen, not em-dash)', async () => {
  await router.navigate('/pick/1')
  setOnline(false)
  render(<OfflineBanner />)
  await waitFor(() => expect(screen.getByText('Offline - work will sync')).toBeInTheDocument())
})
