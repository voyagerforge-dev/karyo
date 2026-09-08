import { useReducer } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery, useMutation } from '@tanstack/react-query'
import { workApi, refId, type TransportOrder } from '@/lib/work-api'
import { ApiError, toast } from '@/lib/api-client'
import { initMove, moveReducer } from '@/move/move-machine'
import { StepHeader } from '@/components/hmi/step-header'
import { ScanField } from '@/components/hmi/scan-field'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'

export function MoveExecution() {
  const { ref = '' } = useParams()
  const nav = useNavigate()
  const id = refId(ref)
  const orderQ = useQuery({ queryKey: ['transport-order', id], queryFn: () => workApi.getTransportOrder(id) })
  if (orderQ.isLoading || !orderQ.data) return <div style={{ padding: 24 }}>Loading…</div>
  return <MoveRunner order={orderQ.data} onDone={() => nav('/')} onRelease={async () => { await workApi.release(ref); nav('/') }} />
}

/** Whole-UL moves only -- there is no qty pad on this screen (unlike receive-execution), by
 *  design: PT17 partial confirms are a desktop-board action (`CompleteTransportOrderRequest.amount`),
 *  not something the floor exposes. The "Destination not set" dead-end below is intentionally
 *  never expected to render for a real operator: PT15 chain successors always carry a suggested
 *  location, so `dest` is only falsy for a manually-created order with neither a destination nor
 *  a suggestion -- an edge case, not a live path. */
function MoveRunner({ order, onDone, onRelease }: { order: TransportOrder; onDone: () => void; onRelease: () => void }) {
  const [state, dispatch] = useReducer(moveReducer, initMove)
  const dest = order.destinationLocationName ?? order.suggestedLocationName
  const complete = useMutation({
    mutationFn: async () => {
      if (order.state < 500) {
        try { await workApi.startTransport(order.id) }
        catch (e) { if (!(e instanceof ApiError && e.problem.status === 409)) throw e }  // already started -> proceed
      }
      return workApi.completeTransport(order.id)
    },
    onSuccess: () => dispatch({ type: 'COMPLETED' }),
    onError: () => toast.error('Could not complete the move — retry'),
  })
  if (state.phase === 'done') {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="ok" message="Move complete" />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
      </div>
    )
  }
  // Honest degradation, mirroring receive-execution's paused gate -- `pausedAt` is orthogonal to
  // `state` (PT18), so a paused move must not offer the scan flow or COMPLETE even though its
  // state/destination look otherwise actionable. Resuming is a desktop-board action; Release
  // stays available so the operator can hand the task back off.
  if (order.pausedAt) {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="warn" message="Task is paused — resume it from the desktop board" />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 18 }}>
      <StepHeader current={1} total={1} label={order.transportType} />
      {state.error && <StatusBanner kind="error" message={state.error} />}
      {state.phase === 'source' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>GET</div>
          <div className="numeric" style={{ fontSize: 36, fontWeight: 650 }}>{order.unitLoadLabel}</div>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>FROM {order.sourceLocationName}</div>
          <ScanField expected={order.unitLoadLabel} label="Scan unit-load"
            onMatch={() => dispatch({ type: 'SOURCE_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'unit-load' })} />
        </>
      )}
      {state.phase === 'destination' && (
        dest ? (
          <>
            <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>PUT AT</div>
            <div className="numeric" style={{ fontSize: 40, fontWeight: 650 }}>{dest}</div>
            <ScanField expected={dest} label="Scan location"
              onMatch={() => dispatch({ type: 'DEST_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'location' })} />
            {state.destConfirmed && (
              <BigButton label={complete.isPending ? 'COMPLETING…' : 'COMPLETE'} disabled={complete.isPending} onClick={() => complete.mutate()} />
            )}
          </>
        ) : (
          <StatusBanner kind="warn" message="Destination not set — not supported yet" />
        )
      )}
      <div style={{ marginTop: 'auto' }}>
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    </div>
  )
}
