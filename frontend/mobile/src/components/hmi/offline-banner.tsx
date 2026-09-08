import { useEffect, useState } from 'react'
import { useOnline } from '@/lib/offline/use-online'
import { countPending } from '@/lib/offline/op-queue'
import { onSyncChange } from '@/lib/offline/sync'
import { router } from '@/routes/router'

// Menu-transaction screens (reached from /menu) queue nothing - they require a live
// connection to complete. The "work will sync" wording is only honest on directed-work
// (pick/move/count/receive) screens where actions actually queue offline.
const MENU_TRANSACTION_ROUTES = new Set([
  '/menu', '/inquiry', '/adhoc-move', '/receive-select', '/adhoc-count', '/pack', '/reprint',
])

// OfflineBanner is mounted as a sibling of <RouterProvider> in App.tsx, not inside the
// routed tree, so useLocation() has no Router context to read. Read the router's own
// location state directly (stripping its basename) and subscribe to changes instead.
function routePath(pathname: string): string {
  const base = router.basename
  if (base && base !== '/' && pathname.startsWith(base)) {
    const stripped = pathname.slice(base.length)
    return stripped === '' ? '/' : stripped
  }
  return pathname
}

export function OfflineBanner() {
  const online = useOnline()
  const [pending, setPending] = useState(0)
  const [pathname, setPathname] = useState(router.state.location.pathname)
  useEffect(() => {
    const refresh = () => { void countPending().then(setPending) }
    refresh()
    const offSync = onSyncChange(refresh)
    window.addEventListener('online', refresh)
    window.addEventListener('offline', refresh)
    const unsubscribe = router.subscribe((state) => setPathname(state.location.pathname))
    return () => {
      offSync()
      window.removeEventListener('online', refresh)
      window.removeEventListener('offline', refresh)
      unsubscribe()
    }
  }, [])
  if (online && pending === 0) return null
  const isMenuTransaction = MENU_TRANSACTION_ROUTES.has(routePath(pathname))
  const msg = online
    ? `Syncing - ${pending} pending`
    : isMenuTransaction
      ? 'OFFLINE - connection required'
      : pending > 0
        ? `Offline - ${pending} pending`
        : 'Offline - work will sync'
  return (
    <div role="status" style={{ padding: '8px 14px', fontSize: 13, fontWeight: 600, textAlign: 'center',
      background: online ? 'var(--floor-rule)' : '#7a2e2e', color: '#fff', letterSpacing: '0.03em' }}>
      {msg}
    </div>
  )
}
