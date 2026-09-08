import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'
import { menuApi, type SortCart, type SortScan } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

const LOT_REQUIRED = 'https://karyo.com/errors/wave-lot-required'

/** The last accepted scan plus what the operator scanned, so UNDO can reverse the right item row. */
type LastScan = SortScan & { itemDataNumber: string; lotNumber?: string }

/** Put wall: scan the cart (a PICKED batch pick container), then scan items one by one; the
 *  server answers the slot to put each unit in. The server is the only source of truth for
 *  remaining/state -- after every successful scan or undo the cart is re-fetched rather than
 *  reconciled client-side (a client-side lot-matching reconciliation drifted stale on any
 *  mismatch, e.g. a null-lot row being sorted with a real lot). Online-only. */
export function SortScreen() {
  const navigate = useNavigate()
  const [cart, setCart] = useState<SortCart | null>(null)
  const [last, setLast] = useState<LastScan | null>(null)
  const [pendingItem, setPendingItem] = useState<string | null>(null)
  const [lot, setLot] = useState('')
  const [amount, setAmount] = useState('1')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** Scans arrive from a wedge or a camera, not a button, so two triggers can land in the same
   *  tick, before any `busy` re-render reaches the scan handler's closure. This ref is checked and
   *  claimed synchronously, so a scan that arrives while a scan or undo is in flight is dropped
   *  instead of double-sorting a unit that was only put in a slot once. */
  const inFlight = useRef(false)

  const fail = (e: unknown, fallback: string) =>
    setError(e instanceof ApiError ? e.problem.detail : e instanceof Error ? e.message : fallback)

  const remaining = cart ? cart.items.reduce((s, i) => s + i.remaining, 0) : 0

  const onScanCart = async (code: string) => {
    setError(null)
    try {
      const ul = await menuApi.getUnitLoadByLabel(code)
      setCart(await menuApi.resolveSortCart(ul.id))
    } catch (e) {
      fail(e, `Cart "${code}" not found`)
    }
  }

  const submitScan = async (itemDataNumber: string, lotNumber?: string) => {
    if (!cart || inFlight.current) return
    const qty = parseFloat(amount)
    if (!(qty > 0)) { setError('Enter a quantity'); return }
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      const scan = await menuApi.sortScan(cart.waveId, { cartUnitLoadId: cart.unitLoadId, itemDataNumber, lotNumber, amount: qty })
      setLast({ ...scan, itemDataNumber, lotNumber })
      setCart(await menuApi.resolveSortCart(cart.unitLoadId))
      setPendingItem(null); setLot(''); setAmount('1')
    } catch (e) {
      if (e instanceof ApiError && e.problem.type === LOT_REQUIRED) { setPendingItem(itemDataNumber); return }
      fail(e, 'Scan failed')
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const undo = async () => {
    if (!cart || !last || inFlight.current) return
    inFlight.current = true
    setBusy(true)
    try {
      await menuApi.sortUndo(cart.waveId, last.scanId)
      setCart(await menuApi.resolveSortCart(cart.unitLoadId))
      setLast(null)
      toast.success('Undone')
    } catch (e) {
      fail(e, 'Undo failed')
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const reset = () => { setCart(null); setLast(null); setPendingItem(null); setLot(''); setAmount('1'); setError(null) }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>SORT</h1>
      {error && <StatusBanner kind="error" message={error} />}
      {!cart && <FreeScanField label="Scan cart" onScan={onScanCart} />}
      {cart && (
        <>
          <p style={{ fontSize: 18, fontWeight: 800 }}>
            {cart.waveNumber} <span style={{ color: 'var(--floor-muted)', fontWeight: 400 }}>{cart.pickOrderNumber}</span>
          </p>
          <p style={{ fontSize: 14, color: 'var(--floor-muted)' }}>
            Remaining on cart: <strong data-testid="sort-remaining">{remaining}</strong>
          </p>
          {remaining === 0 && <StatusBanner kind="ok" message="Cart done. Every unit is sorted." />}
          {last && (
            <div data-testid="sort-slot" style={{ fontSize: 72, fontWeight: 900, textAlign: 'center', padding: 24, background: 'var(--floor-accent)', color: '#fff', borderRadius: 16 }}>
              {last.sortSlot}
              <div style={{ fontSize: 14, fontWeight: 400 }}>{last.destinationKey.split('|')[0]} x {last.amount}</div>
            </div>
          )}
          {remaining > 0 && pendingItem === null && (
            <>
              <label style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)' }}>
                Quantity
                <input aria-label="Quantity" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
                  style={{ width: '100%', minHeight: 56, fontSize: 22, padding: '0 14px', marginTop: 6, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10 }} />
              </label>
              <FreeScanField label="Scan item" onScan={(code) => submitScan(code)} />
            </>
          )}
          {pendingItem !== null && (
            <>
              <label style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)' }}>
                Lot
                <input aria-label="Lot" value={lot} onChange={(e) => setLot(e.target.value)}
                  style={{ width: '100%', minHeight: 56, fontSize: 22, padding: '0 14px', marginTop: 6, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10 }} />
              </label>
              <BigButton label="CONFIRM LOT" disabled={busy || !lot} onClick={() => submitScan(pendingItem, lot)} />
            </>
          )}
          {last && <BigButton variant="ghost" label="UNDO LAST" disabled={busy} onClick={undo} />}
          <BigButton variant="ghost" label="Another cart" onClick={reset} />
        </>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
