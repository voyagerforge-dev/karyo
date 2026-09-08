import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FreeScanField } from '@/components/hmi/free-scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { routeFor } from '@/lib/work-api'
import { menuApi, type LocationInfo } from '@/lib/menu-api'
import { toast, ApiError } from '@/lib/api-client'

/** Ad-hoc count: scan a location, start a blind cycle count session for it, then hand off to
 *  count-execution.tsx via the same WorkRef route the inbox uses (routeFor('COUNT', 'COUNT:<id>')
 *  -- count-execution reads the order by id via refId(ref), no separate claim call needed here. */
export function AdhocCountScreen() {
  const navigate = useNavigate()
  const [location, setLocation] = useState<LocationInfo | null>(null)
  const [starting, setStarting] = useState(false)

  const onScan = async (code: string) => {
    try { setLocation(await menuApi.getLocationByCode(code)) }
    catch { toast.error(`Location "${code}" not found`) }
  }
  const start = async () => {
    setStarting(true)
    try {
      const orderId = await menuApi.startAdhocCount(location!.id)
      navigate(routeFor('COUNT', `COUNT:${orderId}`))
    } catch (e) {
      toast.error(e instanceof ApiError ? e.problem.detail : 'Could not start count')
    } finally {
      setStarting(false)
    }
  }

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 16, minHeight: '100dvh' }}>
      <h1 style={{ fontSize: 20, letterSpacing: '0.06em' }}>AD-HOC COUNT</h1>
      {!location && <FreeScanField label="Scan location to count" onScan={onScan} />}
      {location && (
        <>
          <p style={{ fontSize: 22, fontWeight: 800 }}>{location.name}</p>
          <BigButton label={starting ? 'STARTING…' : 'START COUNT'} disabled={starting} onClick={start} />
          <BigButton variant="ghost" label="Different location" onClick={() => setLocation(null)} />
        </>
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Back to menu" onClick={() => navigate('/menu')} />
      </div>
    </div>
  )
}
