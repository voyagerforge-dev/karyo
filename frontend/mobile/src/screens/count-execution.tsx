import { useReducer } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery, useMutation } from '@tanstack/react-query'
import { workApi, refId, type CountEntry } from '@/lib/work-api'
import { toast } from '@/lib/api-client'
import { initCount, countReducer, type CountState, type CountEvent } from '@/count/count-machine'
import { StepHeader } from '@/components/hmi/step-header'
import { ScanField } from '@/components/hmi/scan-field'
import { QtyPad } from '@/components/hmi/qty-pad'
import { BigButton } from '@/components/hmi/big-button'
import { StatusBanner } from '@/components/hmi/status-banner'

export function CountExecution() {
  const { ref = '' } = useParams()
  const nav = useNavigate()
  const id = refId(ref)
  const entryQ = useQuery({ queryKey: ['count-entry', id], queryFn: () => workApi.getCountEntry(id) })
  if (entryQ.isLoading || !entryQ.data) return <div style={{ padding: 24 }}>Loading…</div>
  // Release can legitimately fail now that a count order can be CANCELLED out from under the
  // operator (St7): the backend 409s on a terminal order. Swallowing the rejection left the
  // operator staring at an unchanged screen with no explanation -- surface it and stay put.
  const onRelease = async () => {
    try {
      await workApi.release(ref)
      nav('/')
    } catch {
      toast.error('Could not release this task — it may already be closed')
    }
  }
  return <CountRunner entry={entryQ.data} onDone={() => nav('/')} onRelease={onRelease} />
}

function CountRunner({ entry, onDone, onRelease }: { entry: CountEntry; onDone: () => void; onRelease: () => void }) {
  // St4: skip lines already resolved elsewhere (e.g. the web app's "unit load missing" action).
  // submitCount now 422s a fresh input against a non-PLANNED line, so the wizard must never
  // walk one -- it only ever sees/prompts for still-open lines.
  const openLines = entry.lines.filter((l) => !l.counted)
  const openEntry: CountEntry = { ...entry, lines: openLines }
  const [state, dispatch] = useReducer((s: CountState, e: CountEvent) => countReducer(openEntry, s, e), initCount)
  const submit = useMutation({
    mutationFn: (lines: { lineId: number; countedAmount: number }[]) => workApi.submitCount(entry.id, lines),
    onSuccess: () => dispatch({ type: 'SUBMITTED' }),
    onError: () => toast.error('Could not submit the count — retry'),
  })
  const markEmpty = useMutation({
    mutationFn: () => workApi.locationEmpty(entry.id),
    onSuccess: () => dispatch({ type: 'SUBMITTED' }),
    onError: () => toast.error('Could not confirm the location empty — retry'),
  })
  if (state.phase === 'done') {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="ok" message="Count submitted" />
        <BigButton label="BACK TO INBOX" onClick={onDone} />
      </div>
    )
  }
  // A ZERO-LINE order (nothing was on record at this location -- e.g. every END_OF_PERIOD order
  // for an empty slot). `submitCount` 422s on an order that never had a line, so the ONLY terminal
  // path is the location-empty op; offering SUBMIT here strands the order and its location freeze.
  if (entry.lines.length === 0) {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="ok" message="Nothing on record here — confirm the location is empty" />
        <BigButton label={markEmpty.isPending ? 'CONFIRMING…' : 'LOCATION EMPTY'} disabled={markEmpty.isPending} onClick={() => markEmpty.mutate()} />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }
  // The order HAS lines, but every one was already resolved elsewhere -- nothing left for this
  // operator to walk. Submitting no inputs is valid here: submitCount only requires input for
  // still-PLANNED lines.
  if (openLines.length === 0) {
    return (
      <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20, height: '100%', justifyContent: 'center' }}>
        <StatusBanner kind="ok" message="Every item here was already counted" />
        <BigButton label={submit.isPending ? 'SUBMITTING…' : 'SUBMIT'} disabled={submit.isPending} onClick={() => submit.mutate([])} />
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    )
  }
  const line = openLines[state.lineIndex]
  const isLast = state.lineIndex === openLines.length - 1
  const onConfirmLine = () => {
    if (!isLast) { dispatch({ type: 'NEXT' }); return }
    const finalCounts = { ...state.counts, [line.lineId]: state.qty }
    submit.mutate(Object.entries(finalCounts).map(([lineId, countedAmount]) => ({ lineId: Number(lineId), countedAmount })))
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 20, gap: 18 }}>
      <StepHeader current={state.lineIndex + 1} total={openLines.length} label="COUNT" />
      <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>AT {entry.locationName}</div>
      {state.error && <StatusBanner kind="error" message={state.error} />}
      {state.phase === 'location' && (
        <ScanField expected={entry.locationName} label="Scan location"
          onMatch={() => dispatch({ type: 'LOCATION_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'location' })} />
      )}
      {state.phase === 'item' && (
        <>
          <div className="numeric" style={{ fontSize: 28 }}>{line.itemDataNumber}</div>
          {line.lotNumber && <div style={{ color: 'var(--floor-muted)' }}>lot {line.lotNumber}</div>}
          {line.serialNumber && <div style={{ color: 'var(--floor-muted)' }}>sn {line.serialNumber}</div>}
          <ScanField expected={line.itemDataNumber} label="Scan item"
            onMatch={() => dispatch({ type: 'ITEM_OK' })} onMismatch={() => dispatch({ type: 'MISMATCH', what: 'item' })} />
        </>
      )}
      {state.phase === 'qty' && (
        <>
          <div style={{ fontSize: 14, color: 'var(--floor-muted)' }}>COUNT</div>
          <QtyPad value={state.qty} onChange={(n) => dispatch({ type: 'SET_QTY', qty: n })} />
        </>
      )}
      <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {state.phase === 'qty' && (
          <BigButton label={submit.isPending ? 'SUBMITTING…' : isLast ? 'SUBMIT' : 'NEXT'} disabled={submit.isPending} onClick={onConfirmLine} />
        )}
        <BigButton variant="ghost" label="Release task" onClick={onRelease} />
      </div>
    </div>
  )
}
