import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { SyncIssues } from '@/screens/sync-issues'
import { enqueue, markFailed, listAll } from '@/lib/offline/op-queue'

beforeEach(async () => {
  await new Promise((r) => { const req = indexedDB.deleteDatabase('karyo-offline'); req.onsuccess = req.onerror = () => r(null) })
})

async function seedFailed() {
  await enqueue({ id: '1', type: 'pick-confirm', method: 'POST', url: '/x', label: 'Confirm pick #42', createdAt: 1, status: 'pending' })
  await markFailed('1', 'task reassigned')
}

test('lists failed ops with label and reason', async () => {
  await seedFailed()
  render(<MemoryRouter><SyncIssues /></MemoryRouter>)
  expect(await screen.findByText('Confirm pick #42')).toBeInTheDocument()
  expect(screen.getByText(/task reassigned/i)).toBeInTheDocument()
})

test('Discard removes the failed op', async () => {
  await seedFailed()
  render(<MemoryRouter><SyncIssues /></MemoryRouter>)
  await userEvent.click(await screen.findByRole('button', { name: /discard/i }))
  await waitFor(async () => expect(await listAll()).toHaveLength(0))
})
