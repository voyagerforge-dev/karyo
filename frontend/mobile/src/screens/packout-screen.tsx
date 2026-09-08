import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'
import { menuApi, type Packout, type ReadyGroup } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

const failure = (e: unknown, fallback: string) =>
  e instanceof ApiError ? e.problem.detail : e instanceof Error ? e.message : fallback

/** The ship-to key is `customerName|street|streetNumber|zipCode|city|country` (Sprint A); the
 *  packer only needs the customer, big. */
const customerOf = (destinationKey: string) => destinationKey.split('|')[0]

const fieldStyle = {
  width: '100%', minHeight: 56, fontSize: 22, padding: '0 14px', marginTop: 6,
  background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10,
} as const

/** Pack-out: pick a READY consolidation group, put its sorted units into containers, close and
 *  label each one, then complete the group shipment.
 *
 *  The server is the only source of truth -- every call returns the whole `Packout` picture
 *  (sorted vs packed derived fresh), so this screen re-renders what came back instead of
 *  reconciling counters client-side, exactly as the sort station does with its cart. Online-only
 *  like every menu transaction: the menu itself refuses to navigate here while disconnected, and
 *  nothing below ever queues an offline op -- a replayed pack line would double-pack a carton. */
export function PackoutScreen() {
  const navigate = useNavigate()
  const [groups, setGroups] = useState<ReadyGroup[] | null>(null)
  const [picked, setPicked] = useState<ReadyGroup | null>(null)
  const [packout, setPackout] = useState<Packout | null>(null)
  const [pendingItem, setPendingItem] = useState<string | null>(null)
  const [amount, setAmount] = useState('1')
  const [weight, setWeight] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** Same claim-synchronously guard as the sort station: scans arrive from a wedge or camera,
   *  not a button, so two can land in one tick before any `busy` re-render is visible. */
  const inFlight = useRef(false)

  /** Nothing here may setState synchronously: this runs from an effect body on mount, and a
   *  synchronous setState there is a cascading render (and a lint error). */
  const loadGroups = useCallback(() => {
    menuApi.listReadyGroups()
      .then(setGroups)
      .catch((e: unknown) => { setGroups([]); setError(failure(e, 'Could not load ready groups')) })
  }, [])

  useEffect(() => { loadGroups() }, [loadGroups])

  const container = packout?.containers.find((c) => c.state === 'OPEN') ?? null
  const remaining = packout ? packout.items.reduce((s, i) => s + i.remaining, 0) : 0

  const openGroup = async (g: ReadyGroup) => {
    if (inFlight.current) return
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      setPicked(g)
      setPackout(await menuApi.openPackout(g.waveId, g.groupId))
    } catch (e) {
      setPicked(null)
      setError(failure(e, 'Could not open pack-out for this group'))
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const newContainer = async (type: string, unitLoadId?: number) => {
    if (!picked || inFlight.current) return
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      const body = unitLoadId === undefined ? { type } : { unitLoadId, type }
      setPackout(await menuApi.packoutContainer(picked.waveId, picked.groupId, body))
    } catch (e) {
      setError(failure(e, 'Could not open a container'))
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  /** Adopt a scanned, empty LPN as the container instead of minting a fresh one. */
  const adoptLpn = async (code: string) => {
    setError(null)
    try {
      const ul = await menuApi.getUnitLoadByLabel(code)
      await newContainer('CARTON', ul.id)
    } catch (e) {
      setError(failure(e, `Unit load "${code}" not found`))
    }
  }

  const addLine = async (itemDataNumber: string, lotNumber?: string) => {
    if (!picked || !container || inFlight.current) return
    const qty = parseFloat(amount)
    if (!(qty > 0)) { setError('Enter a quantity'); return }
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      setPackout(await menuApi.packoutAddLine(picked.waveId, picked.groupId, container.id, { itemDataNumber, lotNumber, amount: qty }))
      setPendingItem(null); setAmount('1')
    } catch (e) {
      setError(failure(e, 'Could not add that to the container'))
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  /** The server takes `lotNumber` optional and picks the slices itself, so a lot is only worth
   *  asking for when the group actually holds more than one lot of the scanned SKU. */
  const onScanItem = (code: string) => {
    if (!packout) return
    const rows = packout.items.filter((i) => i.itemDataNumber === code)
    if (rows.length === 0) { setError(`"${code}" is not on this group`); return }
    const open = rows.filter((i) => i.remaining > 0)
    if (open.length === 0) { setError(`Nothing left to pack for ${code}`); return }
    if (open.length > 1) { setPendingItem(code); return }
    void addLine(code, open[0].lotNumber ?? undefined)
  }

  const closeContainer = async () => {
    if (!picked || !container || inFlight.current) return
    const w = parseFloat(weight)
    if (!(w > 0)) { setError('Enter a weight'); return }
    const unitLoadId = container.unitLoadId
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      setPackout(await menuApi.packoutClose(picked.waveId, picked.groupId, container.id, w))
      setWeight('')
      // The carton is closed and its stock is PACKED whatever the printer does, so a print
      // failure is a toast (503 "no printer configured" / 502) and never a blocked flow --
      // the operator can reprint from menu item 6.
      try {
        await menuApi.printLabel(unitLoadId)
        toast.success('Label sent to printer')
      } catch (e) {
        toast.error(failure(e, 'Print failed'))
      }
    } catch (e) {
      setError(failure(e, 'Could not close the container'))
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const complete = async () => {
    if (!picked || inFlight.current) return
    inFlight.current = true
    setError(null); setBusy(true)
    try {
      setPackout(await menuApi.packoutComplete(picked.waveId, picked.groupId))
    } catch (e) {
      setError(failure(e, 'Could not complete the group'))
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const anotherGroup = () => {
    setPicked(null); setPackout(null); setPendingItem(null)
    setAmount('1'); setWeight(''); setError(null)
    setGroups(null)
    loadGroups()
  }

  const lotChoices = packout && pendingItem
    ? packout.items.filter((i) => i.itemDataNumber === pendingItem && i.remaining > 0)
    : []

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>PACK-OUT</h1>
      {error && <StatusBanner kind="error" message={error} />}

      {!packout && (
        <>
          {groups === null && <p style={{ color: 'var(--floor-muted)' }}>Loading ready groups...</p>}
          {groups?.length === 0 && <p style={{ color: 'var(--floor-muted)' }}>No groups ready for pack-out.</p>}
          {groups?.map((g) => (
            <button
              key={`${g.waveId}-${g.groupId}`} disabled={busy} onClick={() => openGroup(g)}
              style={{
                display: 'flex', alignItems: 'center', gap: 16, minHeight: 72, padding: '0 18px', textAlign: 'left',
                borderRadius: 12, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)',
              }}
            >
              <span style={{ fontWeight: 900, fontSize: 30 }}>{g.sortSlot}</span>
              <span style={{ flex: 1, fontSize: 20, fontWeight: 700 }}>{customerOf(g.destinationKey)}</span>
              <span style={{ fontSize: 14, color: 'var(--floor-muted)' }}>{g.waveNumber}</span>
            </button>
          ))}
        </>
      )}

      {packout && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <span data-testid="packout-slot" style={{ fontSize: 56, fontWeight: 900, padding: '8px 20px', background: 'var(--floor-accent)', color: '#fff', borderRadius: 14 }}>
              {packout.sortSlot}
            </span>
            <span style={{ fontSize: 24, fontWeight: 800 }}>{customerOf(packout.destinationKey)}</span>
          </div>
          <p style={{ fontSize: 14, color: 'var(--floor-muted)' }}>{packout.shipmentNumber}</p>

          {packout.complete && (
            <>
              <StatusBanner kind="ok" message={`Group packed. ${packout.shipmentNumber} is ready to ship.`} />
              <BigButton label="Another group" onClick={anotherGroup} />
            </>
          )}

          {!packout.complete && (
            <>
              <p style={{ fontSize: 14, color: 'var(--floor-muted)' }}>
                Remaining to pack: <strong data-testid="packout-remaining">{remaining}</strong>
              </p>
              <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 6 }}>
                {packout.items.map((i) => (
                  <li key={`${i.itemDataId}-${i.lotNumber ?? ''}`} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 18 }}>
                    <span>{i.itemDataNumber}{i.lotNumber ? ` / ${i.lotNumber}` : ''}</span>
                    <span className="numeric">{i.packed} / {i.sorted}</span>
                  </li>
                ))}
              </ul>

              {!container && (
                <>
                  <BigButton label="NEW CARTON" disabled={busy} onClick={() => newContainer('CARTON')} />
                  <BigButton label="NEW PALLET" disabled={busy} onClick={() => newContainer('PALLET')} />
                  <FreeScanField label="Scan LPN" onScan={adoptLpn} />
                </>
              )}

              {container && (
                <>
                  <section style={{ background: 'var(--floor-surface)', borderRadius: 12, padding: 14 }}>
                    <h2 style={{ fontSize: 18, fontWeight: 800 }}>{container.shippingUnitNumber} <span style={{ color: 'var(--floor-muted)', fontWeight: 400 }}>{container.type}</span></h2>
                    {container.lines.length === 0 && <p style={{ color: 'var(--floor-muted)' }}>Empty.</p>}
                    <ul style={{ listStyle: 'none' }}>
                      {container.lines.map((l, idx) => (
                        <li key={`${l.deliveryOrderId}-${l.itemDataNumber}-${l.lotNumber ?? ''}-${idx}`} style={{ display: 'flex', justifyContent: 'space-between' }}>
                          <span>{l.itemDataNumber}{l.lotNumber ? ` / ${l.lotNumber}` : ''}</span>
                          <span className="numeric">{l.amount}</span>
                        </li>
                      ))}
                    </ul>
                  </section>

                  {pendingItem === null && (
                    <>
                      <label style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)' }}>
                        Quantity
                        <input aria-label="Quantity" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} style={fieldStyle} />
                      </label>
                      <FreeScanField label="Scan item" onScan={onScanItem} />
                    </>
                  )}

                  {pendingItem !== null && (
                    <>
                      <p style={{ fontSize: 18, fontWeight: 700 }}>Which lot of {pendingItem}?</p>
                      {lotChoices.map((i) => (
                        <BigButton
                          key={i.lotNumber ?? 'none'} label={i.lotNumber ?? 'NO LOT'} disabled={busy}
                          onClick={() => addLine(pendingItem, i.lotNumber ?? undefined)}
                        />
                      ))}
                      <BigButton variant="ghost" label="Cancel lot" onClick={() => setPendingItem(null)} />
                    </>
                  )}

                  <label style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)' }}>
                    Weight (kg)
                    <input aria-label="Weight (kg)" inputMode="decimal" value={weight} onChange={(e) => setWeight(e.target.value)} style={fieldStyle} />
                  </label>
                  <BigButton label="CLOSE" disabled={busy} onClick={closeContainer} />
                </>
              )}

              {/* The server 409s `wave-packout-conflict` while anything is unpacked OR a
                  container is still open, so both conditions gate the button here too. */}
              <BigButton label="COMPLETE" disabled={busy || remaining > 0 || container !== null} onClick={complete} />
              <BigButton variant="ghost" label="Another group" onClick={anotherGroup} />
            </>
          )}
        </>
      )}

      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
