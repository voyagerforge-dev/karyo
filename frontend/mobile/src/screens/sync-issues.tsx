import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { listFailed, remove, type QueuedOp } from '@/lib/offline/op-queue'
import { BigButton } from '@/components/hmi/big-button'

export function SyncIssues() {
  const nav = useNavigate()
  const [failed, setFailed] = useState<QueuedOp[]>([])
  const refresh = () => { void listFailed().then(setFailed) }
  useEffect(refresh, [])
  const discard = async (id: string) => { await remove(id); refresh() }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 16 }}>
      <h2 style={{ fontSize: 22, margin: 0 }}>Sync issues ({failed.length})</h2>
      {failed.length === 0 && <div style={{ color: 'var(--floor-muted)' }}>Nothing to resolve.</div>}
      {failed.map((op) => (
        <div key={op.id} style={{ background: 'var(--floor-surface)', border: '1px solid var(--floor-rule)', borderRadius: 10, padding: 14 }}>
          <div style={{ fontWeight: 600 }}>{op.label}</div>
          <div style={{ fontSize: 13, color: '#e08a8a', marginTop: 4 }}>Rejected: {op.error}</div>
          <button onClick={() => discard(op.id)} style={{ marginTop: 10, minHeight: 44, padding: '0 16px', fontWeight: 600, background: 'transparent', color: 'var(--floor-muted)', border: '2px solid var(--floor-rule)', borderRadius: 8 }}>
            Discard
          </button>
        </div>
      ))}
      <div style={{ marginTop: 'auto' }}><BigButton variant="ghost" label="Back to inbox" onClick={() => nav('/')} /></div>
    </div>
  )
}
