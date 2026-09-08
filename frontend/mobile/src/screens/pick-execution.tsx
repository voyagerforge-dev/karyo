import { useReducer, useState } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery, useMutation } from '@tanstack/react-query'
import { workApi, refId, type PickOrder } from '@/lib/work-api'
import { initPick, reducer, type PickState, type PickEvent } from '@/pick/pick-machine'
import { BulkPickRunner } from '@/pick/bulk-pick-runner'
import { StepHeader } from '@/components/hmi/step-header'
import { ScanField } from '@/components/hmi/scan-field'
import { QtyPad } from '@/components/hmi/qty-pad'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'

export function PickExecution() {
  const { ref = '' } = useParams()
  const nav = useNavigate()
  const id = refId(ref)
  const orderQ = useQuery({ queryKey: ['pick-order', id], queryFn: () => workApi.getPickOrder(id) })
  if (orderQ.isLoading || !orderQ.data) return <div style={{ padding: 24 }}>Loading…</div>
  if (orderQ.data.bulk) {
    return <BulkPickRunner pickOrderId={id} onDone={() => nav('/')} onRelease={async () => { await workApi.release(ref); nav('/') }} />
  }
  return <PickRunner order={orderQ.data} onDone={() => nav('/')} onRelease={async () => { await workApi.release(ref); nav('/') }} />
}

function PickRunner({ order, onDone, onRelease }: { order: PickOrder; onDone: () => void; onRelease: () => void }) {
  const [state, dispatch] = useReducer((s: PickState, e: PickEvent) => reducer(order, s, e), order, initPick)
  const [confirmError, setConfirmError] = useState<string>()

  const line = state.phase !== 'done' ? order.picks[state.lineIndex] : undefined
  const stockUnitQ = useQuery({
    queryKey: ['stock-unit', line?.sourceStockUnitId ?? -1],
    queryFn: () => workApi.getStockUnit(line!.sourceStockUnitId),
    enabled: !!line,
  })

  const confirm = useMutation({
    mutationFn: () => workApi.confirmPick(order.picks[state.lineIndex].id, state.qty),
    onSuccess: () => { setConfirmError(undefined); dispatch({ type: 'CONFIRMED' }) },
    onError: () => setConfirmError('Confirm failed — retry'),
  })

  if (state.phase === 'done') {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="ok" message="Pick complete" />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
      </div>
    )
  }

  const locationName = stockUnitQ.data?.locationName

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 18 }}>
      <StepHeader current={state.lineIndex + 1} total={order.picks.length} label="PICK" />
      <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>GO TO</div>
      <div className="numeric" style={{ fontSize: 40, fontWeight: 650 }}>
        {stockUnitQ.isLoading ? '…' : locationName}
      </div>
      {state.error && <StatusBanner kind="error" message={state.error} />}
      {state.phase === 'location' && (
        stockUnitQ.isLoading || !locationName
          ? <div style={{ color: 'var(--floor-muted)', fontSize: 16 }}>Loading location…</div>
          : <ScanField expected={locationName} label="Scan location"
              onMatch={() => dispatch({ type: 'LOCATION_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'location' })} />
      )}
      {state.phase === 'item' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>ITEM</div>
          <div className="numeric" style={{ fontSize: 28 }}>{line?.itemDataNumber}</div>
          <ScanField expected={line?.itemDataNumber ?? ''} label="Scan item"
            onMatch={() => dispatch({ type: 'ITEM_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'item' })} />
        </>
      )}
      {state.phase === 'qty' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>QTY</div>
          <QtyPad value={state.qty} onChange={(n) => dispatch({ type: 'SET_QTY', qty: n })} />
          {confirmError && <StatusBanner kind="error" message={confirmError} />}
        </>
      )}
      <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {state.phase === 'qty' && <BigButton label={confirm.isPending ? 'CONFIRMING…' : 'CONFIRM'} disabled={confirm.isPending} onClick={() => confirm.mutate()} />}
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    </div>
  )
}
