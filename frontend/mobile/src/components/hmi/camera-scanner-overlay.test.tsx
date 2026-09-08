import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CameraScannerOverlay } from '@/components/hmi/camera-scanner-overlay'
import type { BarcodeScanner } from '@/lib/barcode/barcode-scanner'

const fakeScanner = (code: string | null): BarcodeScanner => ({ engine: 'native', decode: async () => code })

function mockGetUserMedia(impl: () => Promise<unknown>) {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn(impl) } })
}
afterEach(() => { Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined }) })

test('fires onResult with the decoded code', async () => {
  mockGetUserMedia(async () => ({ getTracks: () => [{ stop: vi.fn() }] }))
  const onResult = vi.fn()
  render(<CameraScannerOverlay onResult={onResult} onClose={vi.fn()} scanner={fakeScanner('A-01-02')} />)
  await waitFor(() => expect(onResult).toHaveBeenCalledWith('A-01-02'))
})

test('Cancel calls onClose', async () => {
  mockGetUserMedia(async () => ({ getTracks: () => [{ stop: vi.fn() }] }))
  const onClose = vi.fn()
  render(<CameraScannerOverlay onResult={vi.fn()} onClose={onClose} scanner={fakeScanner(null)} />)
  await userEvent.click(screen.getByRole('button', { name: /cancel/i }))
  expect(onClose).toHaveBeenCalled()
})

test('denied permission shows a fallback message', async () => {
  mockGetUserMedia(async () => { throw new DOMException('no', 'NotAllowedError') })
  render(<CameraScannerOverlay onResult={vi.fn()} onClose={vi.fn()} scanner={fakeScanner(null)} />)
  await waitFor(() => expect(screen.getByText(/use the scanner or type the code/i)).toBeInTheDocument())
})
