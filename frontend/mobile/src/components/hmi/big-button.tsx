export function BigButton({ onClick, label, disabled, variant = 'primary' }: {
  onClick: () => void; label: string; disabled?: boolean; variant?: 'primary' | 'ghost'
}) {
  const bg = variant === 'primary' ? 'var(--floor-accent)' : 'transparent'
  return (
    <button onClick={onClick} disabled={disabled}
      style={{ width: '100%', minHeight: 64, fontSize: 22, fontWeight: 600, color: '#fff', background: disabled ? 'var(--floor-rule)' : bg, border: variant === 'ghost' ? '2px solid var(--floor-rule)' : 0, borderRadius: 12 }}>
      {label}
    </button>
  )
}
