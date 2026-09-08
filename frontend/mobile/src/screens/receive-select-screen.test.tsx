import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useParams } from 'react-router'

const listOpenAsns = vi.fn()
const receiptForAsn = vi.fn()
vi.mock('@/lib/menu-api', () => ({
  menuApi: { listOpenAsns: (...a: unknown[]) => listOpenAsns(...a), receiptForAsn: (...a: unknown[]) => receiptForAsn(...a) },
}))
vi.mock('@/components/hmi/free-scan-field', () => ({
  FreeScanField: ({ onScan }: { onScan: (code: string) => void }) => <button onClick={() => onScan('ASN-100')}>scan</button>,
}))

import { ReceiveSelectScreen } from '@/screens/receive-select-screen'

// Stub for /receive/:ref that surfaces the actual ref string the screen navigated with, so the
// test can confirm the WorkRef form ('RECEIVE:<id>') that receive-execution's refId() expects
// (refId does ref.split(':')[1] -- a plain numeric ref would parse to NaN there).
function ReceiveExecutionStub() {
  const { ref } = useParams()
  return <p>receive-execution ref={ref}</p>
}

describe('ReceiveSelectScreen', () => {
  beforeEach(() => {
    listOpenAsns.mockReset()
    receiptForAsn.mockReset()
  })

  it('lists ASNs and navigates to receive execution on select', async () => {
    listOpenAsns.mockResolvedValue([{ id: 4, asnNumber: 'ASN-100', state: 100 }])
    receiptForAsn.mockResolvedValue(55)
    render(
      <MemoryRouter initialEntries={['/receive-select']}>
        <Routes>
          <Route path="/receive-select" element={<ReceiveSelectScreen />} />
          <Route path="/receive/:ref" element={<ReceiveExecutionStub />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('ASN-100'))
    screen.getByText('ASN-100').click()
    await waitFor(() => expect(receiptForAsn).toHaveBeenCalledWith(4))
    await waitFor(() => screen.getByText('receive-execution ref=RECEIVE:55'))
  })

  it('navigates on a matching scan', async () => {
    listOpenAsns.mockResolvedValue([{ id: 4, asnNumber: 'ASN-100', state: 100 }])
    receiptForAsn.mockResolvedValue(55)
    render(
      <MemoryRouter initialEntries={['/receive-select']}>
        <Routes>
          <Route path="/receive-select" element={<ReceiveSelectScreen />} />
          <Route path="/receive/:ref" element={<ReceiveExecutionStub />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('ASN-100'))
    screen.getByText('scan').click()
    await waitFor(() => screen.getByText('receive-execution ref=RECEIVE:55'))
  })

  it('shows an empty message when there are no open ASNs', async () => {
    listOpenAsns.mockResolvedValue([])
    render(
      <MemoryRouter initialEntries={['/receive-select']}>
        <Routes>
          <Route path="/receive-select" element={<ReceiveSelectScreen />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('No open ASNs.'))
  })
})
