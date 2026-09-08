import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { menuApi, type ScanHit, type UnitLoadInfo } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

export function InquiryScreen() {
  const navigate = useNavigate()
  const [hit, setHit] = useState<ScanHit | null>(null)
  const [occupants, setOccupants] = useState<UnitLoadInfo[]>([])
  const [busy, setBusy] = useState(false)

  const onScan = async (code: string) => {
    setBusy(true)
    // Clear stale occupants up front: a new hit (any kind) must never be rendered
    // paired with a previous location's occupant list, even if the occupants fetch
    // below fails independently.
    setOccupants([])
    try {
      const result = await menuApi.resolveScan(code)
      setHit(result)
      if (result.kind === 'location') {
        try {
          setOccupants(await menuApi.getUnitLoadsByLocation(result.location.id))
        } catch {
          // Occupants are an enrichment, not the primary lookup: the location card
          // itself already resolved fine, so don't fail the whole scan over this --
          // just leave the (already-cleared) occupant list empty and say so.
          toast.error('Could not load unit loads for this location')
        }
      }
    } catch (e) {
      toast.error(e instanceof ApiError ? e.problem.detail : 'Lookup failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>INQUIRY</h1>
      <FreeScanField label="Scan unit load or location" onScan={onScan} />
      {busy && <p style={{ color: 'var(--floor-muted)' }}>Looking up…</p>}
      {hit?.kind === 'unitLoad' && (
        <section style={{ background: 'var(--floor-surface)', borderRadius: 12, padding: 14 }}>
          <h2 style={{ fontSize: 24, fontWeight: 800 }}>{hit.ul.labelId}</h2>
          <p style={{ color: 'var(--floor-muted)' }}>{hit.ul.unitLoadTypeName} @ {hit.ul.storageLocationName}</p>
          <ul style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
            {hit.ul.stockUnits.map((s) => (
              <li key={s.id} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 18 }}>
                <span>{s.itemDataNumber}{s.lotNumber ? ` · ${s.lotNumber}` : ''}</span>
                <span style={{ fontWeight: 700 }}>{s.amount}</span>
              </li>
            ))}
            {hit.ul.stockUnits.length === 0 && <li style={{ color: 'var(--floor-muted)' }}>Empty</li>}
          </ul>
        </section>
      )}
      {hit?.kind === 'location' && (
        <section style={{ background: 'var(--floor-surface)', borderRadius: 12, padding: 14 }}>
          <h2 style={{ fontSize: 24, fontWeight: 800 }}>{hit.location.name}</h2>
          {/* LocationResponse carries lockType/lockTypeName, not a `locked` boolean; UNLOCKED is code 0. */}
          <p style={{ color: 'var(--floor-muted)' }}>{hit.location.lockType !== 0 ? 'LOCKED' : 'Unlocked'}</p>
          <ul style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
            {occupants.map((ul) => (
              <li key={ul.id} style={{ fontSize: 18 }}>{ul.labelId}</li>
            ))}
            {occupants.length === 0 && <li style={{ color: 'var(--floor-muted)' }}>No unit loads on this location</li>}
          </ul>
        </section>
      )}
      {hit?.kind === 'none' && (
        <p style={{ color: 'var(--floor-muted)' }}>"{hit.code}": Not a unit load or location.</p>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
