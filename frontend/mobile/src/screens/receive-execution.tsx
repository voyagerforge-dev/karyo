import { useReducer, useState, type CSSProperties } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery, useMutation } from '@tanstack/react-query'
import { workApi, refId, type ReceiveEntry } from '@/lib/work-api'
import { toast, isOfflineError, ApiError } from '@/lib/api-client'
import { useOnline } from '@/lib/offline/use-online'
import { initReceive, receiveReducer, openLines, type ReceiveState, type ReceiveEvent } from '@/receive/receive-machine'
import { StepHeader } from '@/components/hmi/step-header'
import { ScanField } from '@/components/hmi/scan-field'
import { QtyPad } from '@/components/hmi/qty-pad'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'

const CENTERED: CSSProperties = { padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }

export function ReceiveExecution() {
  const { ref = '' } = useParams()
  const nav = useNavigate()
  const id = refId(ref)
  const entryQ = useQuery({ queryKey: ['receive-entry', id], queryFn: () => workApi.getReceiveEntry(id) })
  if (entryQ.isLoading || !entryQ.data) return <div style={{ padding: 24 }}>Loading…</div>
  const onRelease = async () => {
    try { await workApi.release(ref); nav('/') }
    catch { toast.error('Could not release this task — it may already be closed') }
  }
  return <ReceiveRunner entry={entryQ.data} onDone={() => nav('/')} onRelease={onRelease} />
}

function ReceiveRunner({ entry, onDone, onRelease }: { entry: ReceiveEntry; onDone: () => void; onRelease: () => void }) {
  const online = useOnline()
  const [state, dispatch] = useReducer((s: ReceiveState, e: ReceiveEvent) => receiveReducer(entry, s, e), initReceive)
  // Set on a 409 receipt-paused response -- re-renders straight into the same paused
  // dead-end as an already-paused entry (below), without waiting on a refetch.
  const [pausedLocally, setPausedLocally] = useState(false)

  const confirm = useMutation({
    mutationFn: () => {
      const line = entry.lines[state.lineIndex]
      return workApi.receiveLine(entry.id, {
        asnLineId: line.asnLineId,
        amount: state.qty,
        // Receiving on the floor always lands on the receipt's own dock -- there is no location
        // picker here (see the no-dock guard below).
        locationId: entry.dockLocationId!,
        locationName: entry.dockLocationName!,
        unitLoadLabel: state.unitLoadLabel.trim() || undefined,
        allowOverReceipt: false,
      })
    },
    onSuccess: () => dispatch({ type: 'CONFIRM_SUCCESS' }),
    onError: (e) => {
      if (isOfflineError(e)) {
        dispatch({ type: 'CONFIRM_ERROR', message: 'Receiving requires a connection' })
        return
      }
      if (e instanceof ApiError) {
        const kind = e.problem.type.split('/').pop()
        if (kind === 'receipt-paused') {
          setPausedLocally(true)
          return
        }
        if (kind === 'over-receipt') {
          dispatch({ type: 'CONFIRM_ERROR', message: 'Received more than the ASN allows for this line' })
          return
        }
        if (kind === 'receipt-not-receivable') {
          dispatch({ type: 'CONFIRM_ERROR', message: 'This receipt is closed' })
          return
        }
      }
      dispatch({ type: 'CONFIRM_ERROR', message: 'Could not receive this line -- retry' })
    },
  })

  if (state.phase === 'done') {
    return (
      <div style={CENTERED}>
        <StatusBanner kind="ok" message="Receiving complete" />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
      </div>
    )
  }

  // Honest degradation -- there is no location picker on the floor. Without a dock, receiveLine
  // has nowhere to post the stock, so line entry is disabled rather than guessing a location.
  if (!entry.dockLocationId || !entry.dockLocationName) {
    return (
      <div style={CENTERED}>
        <StatusBanner kind="warn" message="This receipt has no dock location — receive it from the desktop workbench" />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }

  if (entry.pausedAt || pausedLocally) {
    return (
      <div style={CENTERED}>
        <StatusBanner kind="warn" message="Receipt is paused" />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }

  // Online-only: receiveLine is not idempotent, so a queued/replayed offline post could
  // double-create stock. No queue here -- the operator must reconnect to receive.
  if (!online) {
    return (
      <div style={CENTERED}>
        <StatusBanner kind="warn" message="Receiving requires a connection" />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }

  if (entry.lines.length === 0) {
    return (
      <div style={CENTERED}>
        <StatusBanner kind="ok" message="Nothing expected on this receipt" />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }

  const open = openLines(entry, state)
  const current = state.lineIndex >= 0 ? entry.lines[state.lineIndex] : undefined
  const stepCurrent = Math.min(entry.lines.length - open.length + 1, entry.lines.length)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 18 }}>
      <StepHeader current={stepCurrent} total={entry.lines.length} label="RECEIVE" />
      <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>AT {entry.dockLocationName}</div>
      <div style={{ fontSize: 12, color: 'var(--floor-muted)' }}>
        {entry.receivedCount} line{entry.receivedCount === 1 ? '' : 's'} received so far
      </div>
      {state.error && <StatusBanner kind="error" message={state.error} />}

      {state.phase === 'pick-line' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {open.map((line) => {
            const lineIndex = entry.lines.indexOf(line)
            return (
              <div key={line.asnLineId} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <button onClick={() => dispatch({ type: 'SELECT_LINE', lineIndex })}
                  style={{ flex: 1, textAlign: 'left', minHeight: 56, padding: 14, background: 'var(--floor-surface)', color: 'var(--floor-ink)', border: '1px solid var(--floor-rule)', borderRadius: 10 }}>
                  <div className="numeric" style={{ fontSize: 20 }}>{line.itemDataNumber}</div>
                  <div style={{ fontSize: 13, color: 'var(--floor-muted)' }}>{line.asnNumber} — {line.remainingAmount} remaining</div>
                </button>
                <button onClick={() => dispatch({ type: 'SKIP_LINE', lineIndex })} aria-label={`Skip ${line.itemDataNumber}`}
                  style={{ minHeight: 56, padding: '0 14px', background: 'transparent', color: 'var(--floor-muted)', border: '2px solid var(--floor-rule)', borderRadius: 10 }}>
                  Skip
                </button>
              </div>
            )
          })}
        </div>
      )}

      {current && (state.phase === 'qty' || state.phase === 'confirm') && (
        <>
          <div className="numeric" style={{ fontSize: 28 }}>{current.itemDataNumber}</div>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>QTY (expected {current.remainingAmount})</div>
          <QtyPad value={state.qty} onChange={(n) => dispatch({ type: 'SET_QTY', qty: n })} />
          {/* Optional: scanning a pre-printed advice label just fills the label -- there is no
              "expected" value to check it against, so any scan/typed value is captured as-is via
              the mismatch channel (an always-empty `expected` never matches a real scan). */}
          <ScanField expected="" label="Scan unit-load label (optional)"
            onMatch={() => {}} onMismatch={(code) => dispatch({ type: 'SET_LABEL', label: code })} />
          {state.unitLoadLabel && <div style={{ fontSize: 13, color: 'var(--floor-muted)' }}>Label: {state.unitLoadLabel}</div>}
        </>
      )}

      <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {state.phase === 'qty' && (
          <BigButton label="REVIEW" disabled={!(state.qty > 0)} onClick={() => dispatch({ type: 'REVIEW' })} />
        )}
        {state.phase === 'confirm' && (
          <BigButton label={confirm.isPending ? 'RECEIVING…' : 'CONFIRM RECEIVE'} disabled={confirm.isPending} onClick={() => confirm.mutate()} />
        )}
        {state.phase === 'pick-line' && (
          <BigButton variant="ghost" label="Finish receiving" onClick={() => dispatch({ type: 'FINISH' })} />
        )}
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    </div>
  )
}
