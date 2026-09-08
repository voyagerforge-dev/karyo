import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { workApi } from '@/lib/work-api'
import { StepHeader } from '@/components/hmi/step-header'
import { ScanField } from '@/components/hmi/scan-field'
import { QtyPad } from '@/components/hmi/qty-pad'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'

type Phase = 'location' | 'item' | 'qty'

/** BULK pick: one aggregated line per source stock unit (SKU + lot + location); CONFIRM sends the
 *  total and the server fans it out to the member orders in allocation order. Short allowed. */
export function BulkPickRunner({ pickOrderId, onDone, onRelease }: { pickOrderId: number; onDone: () => void; onRelease: () => void }) {
  const linesQ = useQuery({ queryKey: ['bulk-lines', pickOrderId], queryFn: () => workApi.getBulkLines(pickOrderId) })
  const [phase, setPhase] = useState<Phase>('location')
  const [qty, setQty] = useState<number | null>(null)
  const [error, setError] = useState<string>()
  const [lastOutcome, setLastOutcome] = useState<string>()
  const [current, setCurrent] = useState(1)
  // Captured once from the first successful getBulkLines result: refetches after each confirm
  // shrink the array as lines are worked off, so the array length can't be re-derived as `total`
  // on every render the way the non-bulk PickRunner uses order.picks.length (that array never
  // shrinks). See pick-execution.tsx's PickRunner for the sibling pattern.
  const [initialTotal, setInitialTotal] = useState<number | null>(null)

  const confirm = useMutation({
    mutationFn: (args: { sourceStockUnitId: number; pickedAmount: number }) => workApi.bulkConfirm(pickOrderId, args),
    onSuccess: (r) => {
      setLastOutcome(`${r.filledSlices} orders filled, ${r.shortSlices} short`)
      setError(undefined); setPhase('location'); setQty(null)
      setCurrent((c) => c + 1)
      linesQ.refetch()
    },
    onError: () => setError('Confirm failed, retry'),
  })

  if (linesQ.isLoading || !linesQ.data) return <div style={{ padding: 24 }}>Loading...</div>
  const lines = linesQ.data
  const line = lines[0]
  if (initialTotal === null) setInitialTotal(lines.length)
  const total = initialTotal ?? lines.length

  if (!line) {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        {lastOutcome && <StatusBanner kind="ok" message={lastOutcome} />}
        <StatusBanner kind="ok" message="Bulk pick complete. Take the cart to the sort station." />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
      </div>
    )
  }
  const value = qty ?? line.plannedTotal

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 18 }}>
      <StepHeader current={current} total={total} label="BULK PICK" />
      {lastOutcome && <StatusBanner kind="ok" message={lastOutcome} />}
      <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>GO TO</div>
      <div className="numeric" style={{ fontSize: 40, fontWeight: 650 }}>{line.locationName}</div>
      <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>{line.unitLoadLabel}</div>
      {error && <StatusBanner kind="error" message={error} />}
      {phase === 'location' && (
        <ScanField expected={line.locationName} label="Scan location" onMatch={() => { setError(undefined); setPhase('item') }} onMismatch={() => setError('Wrong location, try again')} />
      )}
      {phase === 'item' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>ITEM {line.lotNumber ? `(lot ${line.lotNumber})` : ''}</div>
          <div className="numeric" style={{ fontSize: 28 }}>{line.itemDataNumber}</div>
          <ScanField expected={line.itemDataNumber} label="Scan item" onMatch={() => { setError(undefined); setPhase('qty') }} onMismatch={() => setError('Wrong item, try again')} />
        </>
      )}
      {phase === 'qty' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>
            TOTAL FOR <span data-testid="bulk-qty">{line.plannedTotal}</span> ({line.openSlices} orders)
          </div>
          <QtyPad value={value} onChange={setQty} />
        </>
      )}
      <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {phase === 'qty' && (
          <BigButton label={confirm.isPending ? 'CONFIRMING...' : 'CONFIRM'} disabled={confirm.isPending || !(value > 0)}
            onClick={() => confirm.mutate({ sourceStockUnitId: line.sourceStockUnitId, pickedAmount: value })} />
        )}
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    </div>
  )
}
