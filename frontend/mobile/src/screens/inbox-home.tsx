import { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { workApi, routeFor, type WorkItem } from '@/lib/work-api'
import { BigButton } from '@/components/hmi/big-button'
import { TabBar } from '@/components/hmi/tab-bar'
import { useAuth } from '@/auth/auth-provider'
import { ApiError, toast } from '@/lib/api-client'
import { useOnline } from '@/lib/offline/use-online'
import { listFailed } from '@/lib/offline/op-queue'
import { onSyncChange } from '@/lib/offline/sync'

export function InboxHome() {
  const nav = useNavigate()
  const [failedCount, setFailedCount] = useState(0)
  useEffect(() => {
    const refresh = () => { void listFailed().then((f) => setFailedCount(f.length)) }
    refresh()
    const offSync = onSyncChange(refresh)
    return () => { offSync() }
  }, [])
  const qc = useQueryClient()
  const { userName, logout } = useAuth()
  const online = useOnline()
  const mine = useQuery({ queryKey: ['work', 'mine'], queryFn: workApi.mine })
  const getNext = useMutation({
    mutationFn: workApi.next,
    onSuccess: (item: WorkItem | null) => {
      if (!item) { qc.invalidateQueries({ queryKey: ['work'] }); return }
      nav(routeFor(item.workType, item.ref))
    },
    onError: (e) => {
      // 409 = someone claimed it first; any error → surface + let the operator retry
      const msg = e instanceof ApiError && e.problem.status === 409 ? 'Already taken — tap again' : 'Could not get work — retry'
      toast.error(msg)
    },
  })
  const claimed = mine.data ?? []
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100dvh' }}>
      <div style={{ display: 'flex', flexDirection: 'column', flex: 1, padding: 20, gap: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <h2 style={{ fontSize: 22, margin: 0 }}>Hi, {userName?.toUpperCase() ?? 'OPERATOR'}</h2>
          <button onClick={() => logout()}
            style={{ minHeight: 44, padding: '0 16px', fontSize: 14, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', background: 'transparent', color: 'var(--floor-muted)', border: '2px solid var(--floor-rule)', borderRadius: 10 }}>
            Sign out
          </button>
        </div>
        {failedCount > 0 && (
          <button onClick={() => nav('/sync-issues')}
            style={{ minHeight: 44, padding: '0 14px', fontWeight: 600, background: '#7a2e2e', color: '#fff', border: 0, borderRadius: 10 }}>
            ⚠ {failedCount} action{failedCount > 1 ? 's' : ''} failed to sync — review
          </button>
        )}
        <section style={{ flex: 1 }}>
          <div style={{ fontSize: 13, textTransform: 'uppercase', color: 'var(--floor-muted)', marginBottom: 8 }}>My Work ({claimed.length})</div>
          {claimed.map((w) => (
            <button key={w.ref} onClick={() => nav(routeFor(w.workType, w.ref))}
              style={{ display: 'block', width: '100%', textAlign: 'left', minHeight: 56, padding: 14, marginBottom: 8, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '1px solid var(--floor-rule)', borderRadius: 10 }}>
              ▸ {w.summary} — resume
            </button>
          ))}
          {getNext.data === null && <div style={{ color: 'var(--floor-muted)', marginTop: 16 }}>No work available right now.</div>}
        </section>
        <BigButton label={getNext.isPending ? 'GETTING…' : 'GET NEXT TASK'}
          disabled={getNext.isPending || !online} onClick={() => getNext.mutate()} />
      </div>
      <TabBar active="work" />
    </div>
  )
}
