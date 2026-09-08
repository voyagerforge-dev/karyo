import { useNavigate } from 'react-router'

export function TabBar({ active }: { active: 'work' | 'menu' }) {
  const navigate = useNavigate()
  const tab = (key: 'work' | 'menu', label: string, to: string) => (
    <button
      key={key}
      onClick={() => navigate(to)}
      style={{
        flex: 1, minHeight: 64, fontSize: 18, fontWeight: 700, letterSpacing: '0.08em',
        background: active === key ? 'var(--floor-surface)' : 'transparent',
        color: active === key ? 'var(--floor-ink)' : 'var(--floor-muted)',
        border: 'none', borderTop: active === key ? '3px solid var(--floor-accent)' : '3px solid transparent',
      }}
    >{label}</button>
  )
  return (
    <nav style={{ display: 'flex', position: 'sticky', bottom: 0, background: 'var(--floor-bg)', borderTop: '1px solid var(--floor-rule)' }}>
      {tab('work', 'WORK', '/')}
      {tab('menu', 'MENU', '/menu')}
    </nav>
  )
}
