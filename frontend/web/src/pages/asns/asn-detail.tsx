import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  Pencil,
  PackagePlus,
  Plus,
  Printer,
  Send,
  Trash2,
} from 'lucide-react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { TONE_COLOR } from '@/components/master-detail/tones';
import { saveZpl } from '@/lib/document-actions';
import { ProductPicker, type PickedProduct } from '@/pages/orders/product-picker';
import { useCreateGoodsReceipt } from '@/pages/receiving/use-receiving';
import {
  useAsn,
  useReleaseAsn,
  useCancelAsn,
  useFinishAsn,
  useCreateUlAdvice,
  useDeleteUlAdvice,
} from './use-asns';
import { getAsnStatus } from './asn-status';
import { RECEIVING_STATE, type AsnResponse, type AsnLineShortage } from '@/types/receiving';

interface AsnDetailProps {
  asnId: number;
  /** Whether the user holds order-write */
  canWrite: boolean;
  /** Opens the header-edit form (CREATED ASNs only) */
  onEdit: (asn: AsnResponse) => void;
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

/** OrderState line-state name -> tone (mirrors getAsnStatus's numeric mapping). */
function lineTone(stateName: string): EntityTone {
  switch (stateName) {
    case 'RELEASED':
      return 'amber';
    case 'STARTED':
      return 'lime';
    case 'FINISHED':
      return 'blue';
    case 'CANCELED':
      return 'red';
    default:
      return 'grey';
  }
}

/**
 * ASN detail-as-workspace (Task 2 of the ASN Control migration). Ported from
 * the retired Sheet drawer: header + progress, shortage/finished callouts,
 * per-line table, and the state-driven action row (CREATED -> Cancel/Edit/
 * Release; RELEASED|STARTED -> Finish + Open receipt).
 */
export function AsnDetail({ asnId, canWrite, onEdit }: AsnDetailProps) {
  const navigate = useNavigate();
  const { data: asn, isLoading } = useAsn(asnId);
  const releaseMutation = useReleaseAsn();
  const cancelMutation = useCancelAsn();
  const finishMutation = useFinishAsn();
  const createReceipt = useCreateGoodsReceipt();
  const createAdvice = useCreateUlAdvice();
  const deleteAdvice = useDeleteUlAdvice();

  const [confirmCancel, setConfirmCancel] = useState(false);
  const [confirmFinish, setConfirmFinish] = useState(false);
  /** Shortage summary returned by a finish; kept as an amber callout. */
  const [shortages, setShortages] = useState<AsnLineShortage[] | null>(null);

  // UL pre-advice add-row draft.
  const [adviceLabelId, setAdviceLabelId] = useState('');
  const [adviceProduct, setAdviceProduct] = useState<PickedProduct | null>(null);
  const [adviceUnitLoadTypeId, setAdviceUnitLoadTypeId] = useState('');
  const [adviceExpectedAmount, setAdviceExpectedAmount] = useState('');
  const [adviceReason, setAdviceReason] = useState('');

  // Reset the lingering finish summary whenever a different ASN is selected.
  useEffect(() => {
    setShortages(null);
  }, [asnId]);

  if (isLoading || !asn) {
    return (
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  const status = getAsnStatus(asn);
  const isCreated = asn.state === RECEIVING_STATE.CREATED;
  const isReceivable =
    asn.state === RECEIVING_STATE.RELEASED || asn.state === RECEIVING_STATE.STARTED;
  const isFinished = asn.state === RECEIVING_STATE.FINISHED;
  // UL pre-advices may only be added/removed before receiving starts.
  const isAdviceEditable = isCreated || asn.state === RECEIVING_STATE.RELEASED;
  const isMutating =
    releaseMutation.isPending ||
    cancelMutation.isPending ||
    finishMutation.isPending ||
    createReceipt.isPending;

  function handleConfirmCancel() {
    cancelMutation.mutate(asn!.id, {
      onSuccess: () => setConfirmCancel(false),
    });
  }

  function handleConfirmFinish() {
    finishMutation.mutate(asn!.id, {
      onSuccess: ({ shortages: short }) => {
        setConfirmFinish(false);
        setShortages(short);
      },
    });
  }

  function handleOpenReceipt() {
    createReceipt.mutate(
      { asnIds: [asn!.id] },
      {
        onSuccess: (receipt) => {
          navigate(`/receiving/${receipt.id}`);
        },
      },
    );
  }

  function handleAddAdvice() {
    createAdvice.mutate(
      {
        asnId: asn!.id,
        labelId: adviceLabelId.trim() || undefined,
        unitLoadTypeId: adviceUnitLoadTypeId === '' ? undefined : Number(adviceUnitLoadTypeId),
        itemDataId: adviceProduct?.id,
        expectedAmount: adviceExpectedAmount === '' ? undefined : Number(adviceExpectedAmount),
        reasonForReturn: adviceReason.trim() || undefined,
      },
      {
        onSuccess: () => {
          setAdviceLabelId('');
          setAdviceProduct(null);
          setAdviceUnitLoadTypeId('');
          setAdviceExpectedAmount('');
          setAdviceReason('');
        },
      },
    );
  }

  function handleRemoveAdvice(adviceId: number) {
    deleteAdvice.mutate({ asnId: asn!.id, adviceId });
  }

  function handlePrintLabels() {
    saveZpl(`/api/v1/asns/${asn!.id}/ul-labels.zpl`, `asn-${asn!.asnNumber}-ul-labels.zpl`);
  }

  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="font-display numeric text-[20px] font-bold text-foreground">
                {asn.asnNumber}
              </h1>
              <span data-testid="asn-detail-state">
                <StatusPill label={asn.stateName} tone={status.tone} />
              </span>
            </div>
            <p className="mt-1 text-[13px] text-foreground/70">{asn.carrierName ?? 'No carrier'}</p>
          </div>
        </div>

        <div className="mt-5">
          <AttributeGrid
            items={[
              { label: 'External #', value: asn.externalNumber },
              { label: 'Supplier', value: asn.supplierName },
              { label: 'Sender', value: asn.senderName },
              {
                label: 'Expected',
                value: asn.expectedDate ? new Date(asn.expectedDate).toLocaleDateString() : null,
              },
              {
                label: 'Created',
                value: new Date(asn.created).toLocaleString(),
              },
              { label: 'Notes', value: asn.notes },
            ]}
          />
        </div>

        <div className="mt-5 flex items-center gap-3">
          <Progress
            value={Math.min(asn.progressPercent, 100)}
            className="flex-1"
            data-testid="asn-detail-progress"
          />
          <span className="numeric text-[12px] text-muted-foreground">{asn.progressPercent}%</span>
        </div>

        {/* Finish shortage summary -- amber callout */}
        {shortages && shortages.length > 0 && (
          <div
            className="mt-4 rounded-md border border-warning bg-warning/15 p-3"
            data-testid="asn-shortage-callout"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-warning-foreground">
              <AlertTriangle className="size-4" />
              ASN finished short
            </div>
            <ul className="mt-2 space-y-1 text-sm">
              {shortages.map((s) => (
                <li key={s.lineId} className="flex items-baseline gap-2">
                  <span className="font-mono text-[13px]">{s.itemDataNumber}</span>
                  <span className="text-muted-foreground">
                    expected <span className="numeric">{formatAmount(s.expectedAmount)}</span>
                    {', received '}
                    <span className="numeric">{formatAmount(s.receivedAmount)}</span>
                    {', short '}
                    <span className="numeric font-medium text-warning-foreground">
                      {formatAmount(s.shortfall)}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
        {shortages && shortages.length === 0 && (
          <div
            className="mt-4 flex items-center gap-2 rounded-md border border-success bg-success/15 p-3 text-sm font-medium text-success-foreground"
            data-testid="asn-finished-callout"
          >
            <CheckCircle2 className="size-4" />
            ASN fully received.
          </div>
        )}
      </SectionCard>

      {/* Per-line progress table */}
      <SectionCard title="Lines" noPad>
        <Table data-testid="asn-lines-table">
          <TableHeader>
            <TableRow>
              <TableHead>Product</TableHead>
              <TableHead className="text-right">Expected</TableHead>
              <TableHead className="text-right">Received</TableHead>
              <TableHead className="text-right">Remaining</TableHead>
              <TableHead>Progress</TableHead>
              <TableHead>State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {asn.lines.map((line) => (
              <TableRow key={line.id} data-testid={`asn-line-row-${line.lineNumber}`}>
                <TableCell>
                  <span className="font-mono text-[13px]">{line.itemDataNumber}</span>
                  {line.lotNumber && (
                    <span className="ml-2 text-xs text-muted-foreground">Lot {line.lotNumber}</span>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <span className="numeric">{formatAmount(line.expectedAmount)}</span>
                </TableCell>
                <TableCell
                  className="text-right"
                  data-testid={`asn-line-received-${line.lineNumber}`}
                >
                  <span className="numeric">{formatAmount(line.receivedAmount)}</span>
                </TableCell>
                <TableCell className="text-right">
                  <span
                    className={`numeric ${line.remainingAmount > 0 ? 'text-muted-foreground' : 'text-success-foreground'}`}
                  >
                    {formatAmount(line.remainingAmount)}
                  </span>
                </TableCell>
                <TableCell>
                  <div className="flex w-24 items-center gap-2">
                    <div className="h-1.5 flex-1 overflow-hidden rounded bg-background">
                      <div
                        className="h-full rounded"
                        style={{
                          width: `${Math.min(line.progressPercent, 100)}%`,
                          background: TONE_COLOR[lineTone(line.stateName)],
                        }}
                      />
                    </div>
                    <span className="numeric text-[11px] text-muted-foreground">
                      {line.progressPercent}%
                    </span>
                  </div>
                </TableCell>
                <TableCell>
                  <StatusPill label={line.stateName} tone={lineTone(line.stateName)} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </SectionCard>

      {/* UL pre-advices (Karyo-native) -- register expected unit loads ahead of
          receiving and pre-print their labels. Add/remove only while the ASN
          hasn't started receiving (CREATED/RELEASED). */}
      <SectionCard
        title={`Unit load pre-advices (${asn.ulAdvices.length})`}
        action={
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handlePrintLabels}
            disabled={asn.ulAdvices.length === 0}
            data-testid="asn-print-ul-labels"
          >
            <Printer className="size-4" />
            Print labels
          </Button>
        }
      >
        <div className="space-y-4">
          {asn.ulAdvices.length > 0 ? (
            <Table data-testid="asn-ul-advices-table">
              <TableHeader>
                <TableRow>
                  <TableHead>Label</TableHead>
                  <TableHead>Product</TableHead>
                  <TableHead className="text-right">Expected</TableHead>
                  <TableHead>Reason for return</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {asn.ulAdvices.map((advice) => (
                  <TableRow key={advice.id} data-testid={`ul-advice-row-${advice.id}`}>
                    <TableCell>
                      <span className="font-mono text-[13px]">{advice.labelId}</span>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[13px]">
                        {advice.itemDataNumber ?? '—'}
                      </span>
                    </TableCell>
                    <TableCell className="text-right">
                      <span className="numeric">
                        {advice.expectedAmount != null ? formatAmount(advice.expectedAmount) : '—'}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-muted-foreground">
                        {advice.reasonForReturn ?? '—'}
                      </span>
                    </TableCell>
                    <TableCell>
                      <StatusPill label={advice.stateName} tone={lineTone(advice.stateName)} />
                    </TableCell>
                    <TableCell className="text-right">
                      {canWrite && isAdviceEditable && (
                        <Button
                          variant="outline"
                          size="icon"
                          className="size-7 text-destructive"
                          aria-label={`Remove pre-advice ${advice.labelId}`}
                          disabled={deleteAdvice.isPending}
                          onClick={() => handleRemoveAdvice(advice.id)}
                          data-testid={`ul-advice-remove-${advice.id}`}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-sm text-muted-foreground">No unit load pre-advices registered.</p>
          )}

          {canWrite && isAdviceEditable && (
            <div
              className="grid grid-cols-[1fr_1fr_100px_100px_1fr_auto] items-end gap-2 border-t border-border pt-4"
              data-testid="asn-ul-advice-form"
            >
              <div className="space-y-1">
                <Label htmlFor="ul-advice-label">Label</Label>
                <Input
                  id="ul-advice-label"
                  value={adviceLabelId}
                  onChange={(e) => setAdviceLabelId(e.target.value)}
                  placeholder="Auto-generated"
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="ul-advice-product">Product</Label>
                <ProductPicker
                  inputId="ul-advice-product"
                  value={adviceProduct}
                  onChange={setAdviceProduct}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="ul-advice-ul-type">UL type</Label>
                <Input
                  id="ul-advice-ul-type"
                  type="number"
                  min="0"
                  value={adviceUnitLoadTypeId}
                  onChange={(e) => setAdviceUnitLoadTypeId(e.target.value)}
                  placeholder="Optional"
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="ul-advice-expected">Expected</Label>
                <Input
                  id="ul-advice-expected"
                  type="number"
                  min="0"
                  value={adviceExpectedAmount}
                  onChange={(e) => setAdviceExpectedAmount(e.target.value)}
                  placeholder="Optional"
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="ul-advice-reason">Reason for return</Label>
                <Input
                  id="ul-advice-reason"
                  value={adviceReason}
                  onChange={(e) => setAdviceReason(e.target.value)}
                  placeholder="Optional"
                />
              </div>
              <Button
                type="button"
                size="sm"
                onClick={handleAddAdvice}
                disabled={createAdvice.isPending}
                data-testid="asn-ul-advice-add"
              >
                <Plus className="size-4" />
                Add
              </Button>
            </div>
          )}
        </div>
      </SectionCard>

      {/* Actions by state */}
      {canWrite && !isFinished && (
        <div className="flex justify-end gap-2">
          {isCreated && (
            <>
              <Button
                variant="outline"
                className="text-destructive"
                onClick={() => setConfirmCancel(true)}
                disabled={isMutating}
              >
                <Ban className="size-4" />
                Cancel
              </Button>
              <Button variant="outline" onClick={() => onEdit(asn)} disabled={isMutating}>
                <Pencil className="size-4" />
                Edit
              </Button>
              <Button onClick={() => releaseMutation.mutate(asn.id)} disabled={isMutating}>
                <Send className="size-4" />
                {releaseMutation.isPending ? 'Releasing...' : 'Release'}
              </Button>
            </>
          )}
          {isReceivable && (
            <>
              <Button
                variant="outline"
                onClick={() => setConfirmFinish(true)}
                disabled={isMutating}
                data-testid="asn-finish-button"
              >
                <CheckCircle2 className="size-4" />
                Finish
              </Button>
              <Button
                onClick={handleOpenReceipt}
                disabled={isMutating}
                data-testid="asn-open-receipt-button"
              >
                <PackagePlus className="size-4" />
                {createReceipt.isPending ? 'Opening...' : 'Open receipt'}
              </Button>
            </>
          )}
        </div>
      )}

      {/* Cancel confirmation */}
      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel ASN?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will cancel "${asn.asnNumber}". This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep ASN</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirmCancel}>Cancel ASN</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Finish confirmation */}
      <AlertDialog open={confirmFinish} onOpenChange={setConfirmFinish}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Finish ASN?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This force-closes "${asn.asnNumber}". Lines received below their
              expected amount will be closed short and reported.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep Open</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirmFinish} data-testid="asn-finish-confirm">
              Finish ASN
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
