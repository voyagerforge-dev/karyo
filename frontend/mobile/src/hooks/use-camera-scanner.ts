import { useCallback, useEffect, useRef, useState } from 'react'
import { createBarcodeScanner, type BarcodeScanner } from '@/lib/barcode/barcode-scanner'
import { vibrate } from '@/hooks/use-scanner'

export type CameraStatus = 'idle' | 'requesting' | 'scanning' | 'denied' | 'error'

export function useCameraScanner(onDetect: (code: string) => void, scannerOverride?: BarcodeScanner) {
  const [status, setStatus] = useState<CameraStatus>('idle')
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const rafRef = useRef<number | null>(null)
  const doneRef = useRef(false)
  const onDetectRef = useRef(onDetect)
  useEffect(() => { onDetectRef.current = onDetect }, [onDetect])
  // Resolve the scanner exactly once at mount. The lazy initializer short-circuits, so
  // createBarcodeScanner() is NOT called when an override is supplied (test injection).
  const [scanner] = useState<BarcodeScanner>(() => scannerOverride ?? createBarcodeScanner())

  const stop = useCallback(() => {
    doneRef.current = true
    if (rafRef.current != null) { cancelAnimationFrame(rafRef.current); rafRef.current = null }
    streamRef.current?.getTracks().forEach((t) => t.stop())
    streamRef.current = null
  }, [])

  const start = useCallback(async () => {
    doneRef.current = false
    setStatus('requesting')
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      // Unmount or stop() may have fired while awaiting the permission dialog. Without this
      // guard the resolved stream's tracks would never be stopped -> orphaned camera (light stays on).
      if (doneRef.current) { stream.getTracks().forEach((t) => t.stop()); return }
      streamRef.current = stream
      const video = videoRef.current
      if (video) {
        try { video.srcObject = stream } catch { /* jsdom / unsupported srcObject */ }
        try { await video.play() } catch { /* jsdom throws synchronously; real browser rejects — both handled */ }
      }
      setStatus('scanning')
      const tick = async () => {
        if (doneRef.current) return
        const v = videoRef.current
        if (v) {
          try {
            const code = await scanner.decode(v)
            if (code) { doneRef.current = true; vibrate(40); stop(); onDetectRef.current(code); return }
          } catch { /* transient per-frame error; keep looping */ }
        }
        rafRef.current = requestAnimationFrame(tick)
      }
      rafRef.current = requestAnimationFrame(tick)
    } catch (e) {
      const name = (e as DOMException)?.name
      setStatus(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'error')
      stop()
    }
  }, [scanner, stop])

  useEffect(() => () => { doneRef.current = true; stop() }, [stop])

  return { status, videoRef, start, stop }
}
