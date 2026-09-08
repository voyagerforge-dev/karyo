import { useEffect, useRef } from 'react'

/** Keyboard-wedge scanner: rapid keystrokes (<50ms apart) terminated by Enter form one scan. */
export function useScanner(onScan: (code: string) => void) {
  const buf = useRef('')
  const last = useRef(0)
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.tagName === 'INPUT') return
      const now = e.timeStamp
      if (now - last.current > 50) buf.current = ''
      last.current = now
      if (e.key === 'Enter') {
        if (buf.current.length > 0) { onScan(buf.current); buf.current = '' }
        return
      }
      if (e.key.length === 1) buf.current += e.key
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [onScan])
}

export function vibrate(ms: number | number[]) { if ('vibrate' in navigator) navigator.vibrate(ms) }
