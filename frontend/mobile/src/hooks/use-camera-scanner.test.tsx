import { useEffect } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { useCameraScanner } from '@/hooks/use-camera-scanner'
import type { BarcodeScanner } from '@/lib/barcode/barcode-scanner'

const fakeScanner = (code: string | null): BarcodeScanner => ({ engine: 'native', decode: async () => code })

function Harness({ scanner, onDetect }: { scanner: BarcodeScanner; onDetect: (c: string) => void }) {
  const { status, videoRef, start } = useCameraScanner(onDetect, scanner)
  useEffect(() => { void start() }, [start])
  return <><video ref={videoRef} data-testid="v" /><span data-testid="status">{status}</span></>
}

function mockGetUserMedia(impl: () => Promise<unknown>) {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn(impl) } })
}

afterEach(() => { Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined }) })

test('detects a code, fires onDetect, and stops the stream tracks', async () => {
  const stop = vi.fn()
  mockGetUserMedia(async () => ({ getTracks: () => [{ stop }] }))
  const onDetect = vi.fn()
  render(<Harness scanner={fakeScanner('A-01-02')} onDetect={onDetect} />)
  await waitFor(() => expect(onDetect).toHaveBeenCalledWith('A-01-02'))
  expect(stop).toHaveBeenCalled()
})

test('permission denial sets status to denied', async () => {
  mockGetUserMedia(async () => { throw new DOMException('no', 'NotAllowedError') })
  render(<Harness scanner={fakeScanner('X')} onDetect={vi.fn()} />)
  await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('denied'))
})

test('fires onDetect exactly once even though every frame decodes a code', async () => {
  mockGetUserMedia(async () => ({ getTracks: () => [{ stop: vi.fn() }] }))
  const onDetect = vi.fn()
  // A scanner that ALWAYS returns a code: if the doneRef guard is broken, the loop
  // would keep ticking and fire onDetect on every subsequent frame.
  const alwaysScanner: BarcodeScanner = { engine: 'native', decode: async () => 'B-09-09' }
  render(<Harness scanner={alwaysScanner} onDetect={onDetect} />)
  await waitFor(() => expect(onDetect).toHaveBeenCalledWith('B-09-09'))
  // Deterministically flush several animation frames — a broken guard would
  // accumulate additional onDetect calls across these frames; a sealed one will not.
  const frame = () => new Promise((r) => requestAnimationFrame(() => r(undefined)))
  await frame(); await frame(); await frame()
  expect(onDetect).toHaveBeenCalledTimes(1)
})
