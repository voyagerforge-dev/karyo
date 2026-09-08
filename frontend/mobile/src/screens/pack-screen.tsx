import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'
import { menuApi, type UnitLoadInfo } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

const TYPES = ['CARTON', 'PALLET'] as const

type PickOrderHit = { id: number; pickOrderNumber: string; deliveryOrderId: number | null; state: number }

/** Minimal scan-pack: scan a picked unit load, resolve the pick order that targets it,
 *  weigh it, PACK -- one shipping unit per pass. No manifest/dispatch here; those are
 *  separate transactions later in the shipping lifecycle. */
export function PackScreen() {
  const navigate = useNavigate()
  const [ul, setUl] = useState<UnitLoadInfo | null>(null)
  const [pickOrder, setPickOrder] = useState<PickOrderHit | null>(null)
  const [weight, setWeight] = useState('')
  const [type, setType] = useState<string>('CARTON')
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [packing, setPacking] = useState(false)

  const fail = (e: unknown, fallback: string) =>
    setError(e instanceof ApiError ? e.problem.detail : e instanceof Error ? e.message : fallback)

  const onScan = async (code: string) => {
    setError(null)
    try {
      const u = await menuApi.getUnitLoadByLabel(code)
      const po = await menuApi.findPickOrderByTargetUl(u.id)
      if (po.deliveryOrderId === null) { setError('Pick order has no delivery order'); return }
      setUl(u)
      setPickOrder(po)
    } catch (e) {
      fail(e, `Unit load "${code}" not found`)
    }
  }

  const pack = async () => {
    const w = parseFloat(weight)
    if (!(w > 0)) { setError('Enter a weight'); return }
    setError(null)
    setPacking(true)
    try {
      const shipment = await menuApi.openShipment(pickOrder!.deliveryOrderId!)
      await menuApi.packShipment(shipment.id, w, type)
      setDone(true)
      toast.success('Packed')
    } catch (e) {
      fail(e, 'Pack failed')
    } finally {
      setPacking(false)
    }
  }

  const reset = () => { setUl(null); setPickOrder(null); setWeight(''); setType('CARTON'); setDone(false); setError(null) }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>PACK</h1>
      {error && <StatusBanner kind="error" message={error} />}
      {!ul && !done && <FreeScanField label="Scan picked unit load" onScan={onScan} />}
      {ul && pickOrder && !done && (
        <>
          <p style={{ fontSize: 22, fontWeight: 800 }}>
            {ul.labelId} <span style={{ color: 'var(--floor-muted)', fontWeight: 400 }}>{pickOrder.pickOrderNumber}</span>
          </p>
          <label style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)' }}>
            Weight (kg)
            <input
              aria-label="Weight (kg)" inputMode="decimal" value={weight} onChange={(e) => setWeight(e.target.value)}
              style={{ width: '100%', minHeight: 56, fontSize: 22, padding: '0 14px', marginTop: 6, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10 }}
            />
          </label>
          <div style={{ display: 'flex', gap: 8 }}>
            {TYPES.map((t) => (
              <button
                key={t} onClick={() => setType(t)}
                style={{ flex: 1, minHeight: 56, fontSize: 18, fontWeight: 700, borderRadius: 10, background: type === t ? 'var(--floor-accent)' : 'var(--floor-surface)', color: type === t ? '#fff' : 'var(--floor-ink)', border: '2px solid var(--floor-rule)' }}
              >
                {t}
              </button>
            ))}
          </div>
          <BigButton label={packing ? 'PACKING…' : 'PACK'} disabled={packing} onClick={pack} />
        </>
      )}
      {done && ul && (
        <>
          <StatusBanner kind="ok" message={`Packed ${ul.labelId} as ${type}.`} />
          <BigButton label="Pack another unit" onClick={reset} />
        </>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
