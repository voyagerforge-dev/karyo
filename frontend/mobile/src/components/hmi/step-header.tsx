export function StepHeader({ current, total, label }: { current: number; total: number; label: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', borderBottom: '1px solid var(--floor-rule)', paddingBottom: 12 }}>
      <span className="numeric" style={{ fontSize: 20, fontWeight: 650 }}>{label}</span>
      <span className="numeric" style={{ color: 'var(--floor-muted)' }}>{current} / {total}</span>
    </div>
  )
}
