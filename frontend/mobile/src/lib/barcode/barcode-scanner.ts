import type { ReadInputBarcodeFormat } from 'zxing-wasm/reader'
import zxingWasmUrl from 'zxing-wasm/reader/zxing_reader.wasm?url'

// Stable reference for the locateFile override — prepareZXingModule uses shallow
// equality (===) to compare override properties and reuse the cached wasm module
// instance. A new arrow function on each decode call would flush the cache every
// frame. One const at module scope keeps the reference stable.
const _zxingLocateFile = (path: string): string =>
  path.endsWith('.wasm') ? zxingWasmUrl : path

export interface BarcodeScanner {
  readonly engine: 'native' | 'zxing'
  /** Decode one frame from the live video. Returns the barcode text, or null if nothing decodes this frame. */
  decode(video: HTMLVideoElement): Promise<string | null>
}

/** Barcode Detection API format identifiers for supported 1D and 2D labels. */
export const SCAN_FORMATS = ['code_128', 'ean_13', 'ean_8', 'code_39', 'qr_code', 'data_matrix'] as const

/** zxing-wasm format identifiers, parallel to SCAN_FORMATS. */
const ZXING_FORMATS: ReadInputBarcodeFormat[] = ['Code128', 'EAN-13', 'EAN-8', 'Code39', 'QRCode', 'DataMatrix']

type BarcodeDetectorLike = { detect(src: CanvasImageSource): Promise<Array<{ rawValue: string }>> }
type BarcodeDetectorCtor = new (opts: { formats: readonly string[] }) => BarcodeDetectorLike

export function nativeSupported(): boolean {
  return typeof window !== 'undefined' && 'BarcodeDetector' in window
}

class NativeBarcodeScanner implements BarcodeScanner {
  readonly engine = 'native' as const
  private detector: BarcodeDetectorLike

  constructor() {
    const raw = (window as unknown as { BarcodeDetector: unknown }).BarcodeDetector
    const opts = { formats: SCAN_FORMATS }
    // Production: BarcodeDetector is a class constructor (requires `new`).
    // Test environments (vitest 4 + arrow-fn mocks) cannot be used with `new`,
    // so fall back to calling as a factory function.
    try {
      this.detector = new (raw as BarcodeDetectorCtor)(opts)
    } catch {
      this.detector = (raw as (o: { formats: readonly string[] }) => BarcodeDetectorLike)(opts)
    }
  }

  async decode(video: HTMLVideoElement): Promise<string | null> {
    const codes = await this.detector.detect(video)
    return codes.length > 0 ? codes[0].rawValue : null
  }
}

class ZxingBarcodeScanner implements BarcodeScanner {
  readonly engine = 'zxing' as const

  async decode(video: HTMLVideoElement): Promise<string | null> {
    const w = video.videoWidth, h = video.videoHeight
    if (!w || !h) return null
    const canvas = document.createElement('canvas')
    canvas.width = w; canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.drawImage(video, 0, 0, w, h)
    const imageData = ctx.getImageData(0, 0, w, h)
    const reader = await import('zxing-wasm/reader')
    // Point zxing at the locally bundled wasm (precached by the service worker)
    // instead of the jsDelivr CDN default so the camera fallback works offline.
    reader.prepareZXingModule({ overrides: { locateFile: _zxingLocateFile } })
    const results = await reader.readBarcodes(imageData, { tryHarder: true, formats: ZXING_FORMATS })
    return results.length > 0 ? results[0].text : null
  }
}

export function createBarcodeScanner(): BarcodeScanner {
  return nativeSupported() ? new NativeBarcodeScanner() : new ZxingBarcodeScanner()
}
