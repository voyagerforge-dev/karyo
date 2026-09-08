import { createBarcodeScanner, nativeSupported } from '@/lib/barcode/barcode-scanner'

afterEach(() => { delete (window as unknown as Record<string, unknown>).BarcodeDetector })

test('nativeSupported reflects BarcodeDetector presence', () => {
  delete (window as unknown as Record<string, unknown>).BarcodeDetector
  expect(nativeSupported()).toBe(false)
  ;(window as unknown as Record<string, unknown>).BarcodeDetector = vi.fn()
  expect(nativeSupported()).toBe(true)
})

test('factory builds a native scanner that maps the first rawValue', async () => {
  const detect = vi.fn().mockResolvedValue([{ rawValue: 'A-01-02' }, { rawValue: 'B-02' }])
  ;(window as unknown as Record<string, unknown>).BarcodeDetector = vi.fn(() => ({ detect }))
  const scanner = createBarcodeScanner()
  expect(scanner.engine).toBe('native')
  const video = document.createElement('video')
  expect(await scanner.decode(video)).toBe('A-01-02')
  detect.mockResolvedValueOnce([])
  expect(await scanner.decode(video)).toBeNull()
})

test('factory falls back to zxing when BarcodeDetector is absent', () => {
  delete (window as unknown as Record<string, unknown>).BarcodeDetector
  expect(createBarcodeScanner().engine).toBe('zxing')
})
