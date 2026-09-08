import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { menuApi, type UnitLoadInfo } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

export function ReprintScreen() {
  const navigate = useNavigate()
  const [ul, setUl] = useState<UnitLoadInfo | null>(null)
  const [printing, setPrinting] = useState(false)

  const onScan = async (code: string) => {
    try { setUl(await menuApi.getUnitLoadByLabel(code)) }
    catch { toast.error(`Unit load "${code}" not found`) }
  }
  const print = async () => {
    setPrinting(true)
    try { await menuApi.printLabel(ul!.id); toast.success('Label sent to printer') }
    catch (e) { toast.error(e instanceof ApiError ? e.problem.detail : 'Print failed') }
    finally { setPrinting(false) }
  }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>REPRINT LABEL</h1>
      {!ul && <FreeScanField label="Scan unit load" onScan={onScan} />}
      {ul && (
        <>
          <section style={{ background: 'var(--floor-surface)', borderRadius: 12, padding: 14 }}>
            <h2 style={{ fontSize: 24, fontWeight: 800 }}>{ul.labelId}</h2>
            <p style={{ color: 'var(--floor-muted)' }}>{ul.unitLoadTypeName} @ {ul.storageLocationName}</p>
          </section>
          <BigButton label={printing ? 'PRINTING…' : 'PRINT'} disabled={printing} onClick={print} />
          <BigButton variant="ghost" label="Different unit load" onClick={() => setUl(null)} />
        </>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
