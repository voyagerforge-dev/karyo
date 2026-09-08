import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'
import { menuApi, type UnitLoadInfo, type LocationInfo } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

type Phase = 'source' | 'target' | 'confirm' | 'done'

/** Ad-hoc move: scan a unit load, scan a destination location, confirm -- then create+start+complete
 *  a MOVE transport order in one shot. There's no separate dispatch/release step like a normal
 *  transport order because the operator is physically doing the move themselves right now. */
export function AdhocMoveScreen() {
  const navigate = useNavigate()
  const [phase, setPhase] = useState<Phase>('source')
  const [ul, setUl] = useState<UnitLoadInfo | null>(null)
  const [dest, setDest] = useState<LocationInfo | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  const fail = (e: unknown, fallback: string) =>
    setError(e instanceof ApiError ? e.problem.detail : fallback)

  const onSource = async (code: string) => {
    setError(null)
    try { setUl(await menuApi.getUnitLoadByLabel(code)); setPhase('target') }
    catch (e) { fail(e, `Unit load "${code}" not found`) }
  }
  const onTarget = async (code: string) => {
    setError(null)
    try { setDest(await menuApi.getLocationByCode(code)); setPhase('confirm') }
    catch (e) { fail(e, `Location "${code}" not found`) }
  }
  const confirm = async () => {
    setError(null)
    setConfirming(true)
    try {
      await menuApi.adhocMove(ul!.id, dest!.id, dest!.name)
      setPhase('done')
      toast.success('Moved')
    } catch (e) {
      fail(e, 'Move failed')
    } finally {
      setConfirming(false)
    }
  }
  const reset = () => { setPhase('source'); setUl(null); setDest(null); setError(null) }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>AD-HOC MOVE</h1>
      {ul && (
        <p style={{ fontSize: 22, fontWeight: 800 }}>
          {ul.labelId} <span style={{ color: 'var(--floor-muted)', fontWeight: 400 }}>from {ul.storageLocationName}</span>
        </p>
      )}
      {dest && <p style={{ fontSize: 22, fontWeight: 800 }}>{dest.name}</p>}
      {error && <StatusBanner kind="error" message={error} />}
      {phase === 'source' && <FreeScanField label="Scan unit load" onScan={onSource} />}
      {phase === 'target' && <FreeScanField label="Scan target location" onScan={onTarget} />}
      {phase === 'confirm' && (
        <BigButton label={confirming ? 'MOVING…' : 'CONFIRM MOVE'} disabled={confirming} onClick={confirm} />
      )}
      {phase === 'done' && ul && dest && (
        <>
          <StatusBanner kind="ok" message={`Moved ${ul.labelId} to ${dest.name}.`} />
          <BigButton label="Move another" onClick={reset} />
        </>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
