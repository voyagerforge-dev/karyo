const COLORS = {
  ok: ['var(--floor-ok)', 'var(--floor-ok-ink)'],
  warn: ['var(--floor-warn)', '#fff'],
  error: ['var(--floor-error)', 'var(--floor-error-ink)'],
} as const
export function StatusBanner({ kind, message }: { kind: keyof typeof COLORS; message: string }) {
  const [bg, fg] = COLORS[kind]
  return <div role="status" style={{ background: bg, color: fg, padding: 16, fontSize: 18, fontWeight: 600, borderRadius: 10 }}>{message}</div>
}
