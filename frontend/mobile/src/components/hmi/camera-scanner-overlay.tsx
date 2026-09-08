import { useEffect } from 'react'
import { useCameraScanner } from '@/hooks/use-camera-scanner'
import { BigButton } from '@/components/hmi/big-button'
import type { BarcodeScanner } from '@/lib/barcode/barcode-scanner'

export function CameraScannerOverlay({ onResult, onClose, scanner }: {
  onResult: (code: string) => void
  onClose: () => void
  scanner?: BarcodeScanner
}) {
  const { status, videoRef, start } = useCameraScanner(onResult, scanner)
  useEffect(() => { void start() }, [start])
  const failed = status === 'denied' || status === 'error'
  return (
    <div role="dialog" aria-label="Camera scanner"
      style={{ position: 'fixed', inset: 0, zIndex: 1000, background: '#000', display: 'flex', flexDirection: 'column' }}>
      {failed ? (
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
          textAlign: 'center', color: 'var(--floor-ink, #fff)', fontSize: 20 }}>
          Camera unavailable — use the scanner or type the code.
        </div>
      ) : (
        <div style={{ flex: 1, position: 'relative' }}>
          <video ref={videoRef} autoPlay playsInline muted
            style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          <div aria-hidden style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
            width: '70%', height: 140, border: '3px solid var(--floor-accent, #4ade80)', borderRadius: 12,
            boxShadow: '0 0 0 100vmax rgba(0,0,0,0.35)' }} />
        </div>
      )}
      <div style={{ padding: 16 }}>
        <BigButton variant="ghost" label="Cancel" onClick={onClose} />
      </div>
    </div>
  )
}
