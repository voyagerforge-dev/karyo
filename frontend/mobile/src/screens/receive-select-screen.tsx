import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { BigButton } from '@/components/hmi/big-button'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { menuApi, type OpenAsn } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

export function ReceiveSelectScreen() {
  const navigate = useNavigate()
  const [asns, setAsns] = useState<OpenAsn[] | null>(null)

  useEffect(() => { menuApi.listOpenAsns().then(setAsns).catch(() => toast.error('Could not load ASNs')) }, [])

  const select = async (asn: OpenAsn) => {
    try {
      const receiptId = await menuApi.receiptForAsn(asn.id)
      // receive-execution's refId() parses `ref.split(':')[1]` -- the WorkRef form, not a
      // plain numeric id (a plain id would parse to NaN there).
      navigate(`/receive/${encodeURIComponent(`RECEIVE:${receiptId}`)}`)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.problem.detail : 'Could not open receipt')
    }
  }

  const onScan = (code: string) => {
    const hit = asns?.find((a) => a.asnNumber.toUpperCase() === code)
    if (hit) select(hit)
    else toast.error(`No open ASN "${code}"`)
  }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>RECEIVE: SELECT ASN</h1>
      <FreeScanField label="Scan ASN number" onScan={onScan} />
      {asns === null && <p style={{ color: 'var(--floor-muted)' }}>Loading…</p>}
      {asns?.map((a) => (
        <button key={a.id} onClick={() => select(a)}
          style={{ minHeight: 64, textAlign: 'left', padding: '0 16px', fontSize: 20, borderRadius: 12, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)' }}>
          {a.asnNumber}{a.supplierName ? ` · ${a.supplierName}` : ''}
        </button>
      ))}
      {asns?.length === 0 && <p style={{ color: 'var(--floor-muted)' }}>No open ASNs.</p>}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
