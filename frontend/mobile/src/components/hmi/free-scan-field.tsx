import { useState } from 'react'
import { useScanner, vibrate } from '@/hooks/use-scanner'
import { BigButton } from '@/components/hmi/big-button'
import { CameraScannerOverlay } from '@/components/hmi/camera-scanner-overlay'

const norm = (s: string) => s.trim().toUpperCase()

/** Capture-any-value cousin of ScanField: no expected-match, emits every scan (wedge, manual,
 *  camera all converge on the same submit seam). Used by menu transactions where the scanned
 *  value IS the input (inquiry, move source/target, reprint, pack). */
export function FreeScanField({ label, onScan }: { label: string; onScan: (code: string) => void }) {
  const [manual, setManual] = useState('')
  const [camOpen, setCamOpen] = useState(false)
  const submit = (code: string) => { if (norm(code)) { vibrate(40); onScan(norm(code)) } }
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
      {cameraAvailable && <BigButton variant="ghost" label="Scan with camera" onClick={() => setCamOpen(true)} />}
      {camOpen && (
        <CameraScannerOverlay
          onResult={(code) => { setCamOpen(false); submit(code) }}
          onClose={() => setCamOpen(false)}
        />
      )}
    </div>
  )
}
