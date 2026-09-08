import { useState } from 'react'
import { useScanner, vibrate } from '@/hooks/use-scanner'
import { BigButton } from '@/components/hmi/big-button'
import { CameraScannerOverlay } from '@/components/hmi/camera-scanner-overlay'

const norm = (s: string) => s.trim().toUpperCase()

export function ScanField({ expected, label, onMatch, onMismatch }: {
  expected: string; label: string; onMatch: () => void; onMismatch: (scanned: string) => void
}) {
  const [manual, setManual] = useState('')
  const [camOpen, setCamOpen] = useState(false)
  const submit = (code: string) => {
    if (norm(code) === norm(expected)) { vibrate(40); onMatch() }
    else { vibrate([60, 40, 60]); onMismatch(code) }
  }
  useScanner(submit)
  const cameraAvailable = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <label style={{ fontSize: 13, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--floor-muted)' }}>{label}</label>
      <form onSubmit={(e) => { e.preventDefault(); if (manual) { submit(manual); setManual('') } }}>
        <input
          aria-label={label} value={manual} onChange={(e) => setManual(e.target.value)}
          placeholder="Scan or type…"
          style={{ width: '100%', minHeight: 56, fontSize: 22, padding: '0 14px', background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10 }}
          className="numeric"
        />
      </form>
      {cameraAvailable && (
        <BigButton variant="ghost" label="Scan with camera" onClick={() => setCamOpen(true)} />
      )}
      {camOpen && (
        <CameraScannerOverlay
          onResult={(code) => { setCamOpen(false); submit(code) }}
          onClose={() => setCamOpen(false)}
        />
      )}
    </div>
  )
}
