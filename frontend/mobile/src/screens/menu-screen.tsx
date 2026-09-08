import { useNavigate } from 'react-router'
import { useMenu } from '@/menu/use-menu'
import { TabBar } from '@/components/hmi/tab-bar'
import { toast } from '@/lib/api-client'

export function MenuScreen() {
  const navigate = useNavigate()
  const { items, online } = useMenu()
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100dvh' }}>
      <main style={{ flex: 1, padding: 16, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {items.map((m) => (
          <button
            key={m.id}
            onClick={() => (online ? navigate(m.route) : toast.error('Offline: menu transactions need a connection'))}
            style={{
              display: 'flex', alignItems: 'center', gap: 16, minHeight: 72, padding: '0 18px',
              fontSize: 22, textAlign: 'left', borderRadius: 12,
              background: 'var(--floor-surface)', color: online ? 'var(--floor-ink)' : 'var(--floor-muted)',
              border: '2px solid var(--floor-rule)', opacity: online ? 1 : 0.6,
            }}
          >
            <span style={{ fontWeight: 800, fontSize: 26 }}>{m.num}</span>
            <span style={{ flex: 1 }}>{m.label}</span>
            {!online && <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--floor-muted)' }}>OFFLINE</span>}
          </button>
        ))}
        {items.length === 0 && <p style={{ color: 'var(--floor-muted)' }}>No transactions available for your role.</p>}
      </main>
      <TabBar active="menu" />
    </div>
  )
}
