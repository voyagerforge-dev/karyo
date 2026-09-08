import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ScanField } from '@/components/hmi/scan-field'

test('manual entry matching expected fires onMatch', async () => {
  const onMatch = vi.fn(); const onMismatch = vi.fn()
  render(<ScanField expected="A-01-02" label="Scan location" onMatch={onMatch} onMismatch={onMismatch} />)
  await userEvent.type(screen.getByLabelText('Scan location'), 'a-01-02{Enter}')
  expect(onMatch).toHaveBeenCalled(); expect(onMismatch).not.toHaveBeenCalled()
})

test('wrong code fires onMismatch with the scanned value', async () => {
  const onMatch = vi.fn(); const onMismatch = vi.fn()
  render(<ScanField expected="A-01-02" label="Scan location" onMatch={onMatch} onMismatch={onMismatch} />)
  await userEvent.type(screen.getByLabelText('Scan location'), 'B-09-09{Enter}')
  expect(onMismatch).toHaveBeenCalledWith('B-09-09'); expect(onMatch).not.toHaveBeenCalled()
})

import { render as render2, screen as screen2 } from '@testing-library/react'
import userEvent2 from '@testing-library/user-event'

// Stub the overlay so the ScanField wiring is tested without real camera APIs.
vi.mock('@/components/hmi/camera-scanner-overlay', () => ({
  CameraScannerOverlay: ({ onResult }: { onResult: (c: string) => void }) => (
    <button onClick={() => onResult('A-01-02')}>fire-camera</button>
  ),
}))

function withCamera() {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } })
}
function withoutCamera() {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
}
afterEach(withoutCamera)

test('camera button is hidden when getUserMedia is unavailable', () => {
  withoutCamera()
  render2(<ScanField expected="A-01-02" label="Scan location" onMatch={vi.fn()} onMismatch={vi.fn()} />)
  expect(screen2.queryByRole('button', { name: /scan with camera/i })).toBeNull()
})

test('a camera result for the expected code fires onMatch', async () => {
  withCamera()
  const onMatch = vi.fn()
  render2(<ScanField expected="A-01-02" label="Scan location" onMatch={onMatch} onMismatch={vi.fn()} />)
  await userEvent2.click(screen2.getByRole('button', { name: /scan with camera/i }))
  await userEvent2.click(screen2.getByRole('button', { name: /fire-camera/i }))
  expect(onMatch).toHaveBeenCalled()
})

test('a camera result for a wrong code fires onMismatch', async () => {
  withCamera()
  const onMismatch = vi.fn()
  render2(<ScanField expected="Z-99-99" label="Scan location" onMatch={vi.fn()} onMismatch={onMismatch} />)
  await userEvent2.click(screen2.getByRole('button', { name: /scan with camera/i }))
  await userEvent2.click(screen2.getByRole('button', { name: /fire-camera/i }))
  expect(onMismatch).toHaveBeenCalledWith('A-01-02')
})
