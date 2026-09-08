export function QtyPad({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
      <button aria-label="decrement" onClick={() => onChange(Math.max(0, value - 1))} style={{ minWidth: 64, minHeight: 64, fontSize: 28 }}>−</button>
      <input aria-label="quantity" className="numeric" type="number" value={value} onChange={(e) => onChange(Number(e.target.value))}
        style={{ flex: 1, minHeight: 64, fontSize: 36, textAlign: 'center', background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '2px solid var(--floor-rule)', borderRadius: 10 }} />
      <button aria-label="increment" onClick={() => onChange(value + 1)} style={{ minWidth: 64, minHeight: 64, fontSize: 28 }}>+</button>
    </div>
  )
}
