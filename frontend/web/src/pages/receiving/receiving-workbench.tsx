import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ArrowLeft, CheckCircle2, PackageCheck, Play } from 'lucide-react';
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
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { humanizeStateName } from '@/lib/state-variants';
import { usePermissions } from '@/hooks/use-permissions';
import { useAsnsByIds } from '@/pages/asns/use-asns';
import { ReceiveForm } from './receive-form';
import { useGoodsReceipt, useFinishReceipt, useResumeReceipt } from './use-receiving';
import { getReceiptStatus, RECEIPT_LOCK_LABELS } from './receiving-status';
import { RECEIVING_STATE, GOODS_RECEIPT_TYPE, type AsnLineResponse } from '@/types/receiving';

/** An expected line merged from one of the receipt's (possibly several) linked ASNs. */
interface ExpectedLine extends AsnLineResponse {
  asnNumber: string;
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

function fmtDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/**
 * Full-page receiving workbench (/receiving/{id}) — the operator surface.
 * Left pane: expected lines (ASN-bound) or a blind product picker. Right pane:
 * the receive-line form. Below: received lines + Finish.
 */
export function ReceivingWorkbench() {
  const { id } = useParams<{ id: string }>();
  const receiptId = id ? Number(id) : undefined;
  const navigate = useNavigate();
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('order-write');

  const { data: receipt, isLoading } = useGoodsReceipt(receiptId);
  const { data: linkedAsns } = useAsnsByIds(receipt?.asns.map((a) => a.id) ?? []);
  const finishReceipt = useFinishReceipt();
  const resumeReceipt = useResumeReceipt();

  /** Currently selected expected line to receive against (null = blind line). */
  const [selectedLine, setSelectedLine] = useState<ExpectedLine | null>(null);
  /** Bumps to remount the receive form (clears its fields on prefill change). */
  const [formKey, setFormKey] = useState(0);
  const [confirmFinish, setConfirmFinish] = useState(false);

  function selectLine(line: ExpectedLine | null) {
    setSelectedLine(line);
    setFormKey((k) => k + 1);
  }

  function handleReceived() {
    // Clear the prefill so the next line starts fresh; data refetches via query
    // invalidation in the receive hook.
    selectLine(null);
  }

  if (isLoading || !receipt) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const status = getReceiptStatus(receipt);
  const isOpen =
    receipt.state === RECEIVING_STATE.CREATED || receipt.state === RECEIVING_STATE.STARTED;
  const isPaused = Boolean(receipt.pausedAt);
  // Expected lines merged across every linked ASN (V424 M2M) — each line carries
  // its source ASN number so the table can distinguish them.
  const expectedLines: ExpectedLine[] = linkedAsns.flatMap((a) =>
    a.lines
      .filter((l) => l.remainingAmount > 0)
      .map((l) => ({ ...l, asnNumber: a.asnNumber })),
  );

  return (
    <div className="space-y-4" data-testid="receiving-workbench">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/receiving')}>
            <ArrowLeft className="size-4" />
            <span className="sr-only">Back to receiving</span>
          </Button>
          <h1 className="font-display numeric text-2xl font-bold text-foreground">
            {receipt.receiptNumber}
          </h1>
          <span data-testid="receipt-state">
            <StatusPill label={receipt.stateName} tone={status.tone} />
          </span>
          {receipt.receiptType === GOODS_RECEIPT_TYPE.RETOUR && (
            <StatusPill label="RETOUR" tone="amber" />
          )}
          {receipt.asns.map((a) => (
            <Link
              key={a.id}
              to={`/asns?q=${encodeURIComponent(a.asnNumber)}`}
              className="text-sm text-primary underline-offset-4 hover:underline"
            >
              ASN {a.asnNumber}
            </Link>
          ))}
          {receipt.operatorId && (
            <span className="text-[13px] text-muted-foreground">
              Claimed by {receipt.operatorId}
            </span>
          )}
        </div>
        {canWrite && isOpen && (
          <Button
            onClick={() => setConfirmFinish(true)}
            disabled={finishReceipt.isPending || isPaused}
            data-testid="finish-receipt-button"
          >
            <CheckCircle2 className="size-4" />
            Finish receipt
          </Button>
        )}
      </div>

      {/* Pause banner -- server enforces the block via 409, this communicates
          it. pausedAt isn't cleared by cancel/finish, so a closed receipt can
          still carry a stale pause stamp; gate on `isOpen` or this can
          co-render with the "no further receiving" callout below. */}
      {isOpen && isPaused && (
        <div
          className="flex items-center justify-between gap-3 rounded-md border border-warning bg-warning/15 p-3 text-sm font-medium text-warning-foreground"
          data-testid="workbench-paused-banner"
        >
          <span>
            Paused since {fmtDateTime(receipt.pausedAt)} — resume to continue receiving
          </span>
          {canWrite && (
            <Button
              size="sm"
              onClick={() => resumeReceipt.mutate(receipt.id)}
              disabled={resumeReceipt.isPending}
              data-testid="workbench-resume-button"
            >
              <Play className="size-4" />
              Resume
            </Button>
          )}
        </div>
      )}

      {isOpen ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {/* Left pane: Expected */}
          <div data-testid="expected-pane">
            <SectionCard title="Expected">
              {receipt.asns.length > 0 ? (
                expectedLines.length > 0 ? (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Product</TableHead>
                        <TableHead>ASN</TableHead>
                        <TableHead className="text-right">Remaining</TableHead>
                        <TableHead />
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {expectedLines.map((line) => (
                        <TableRow
                          key={line.id}
                          data-testid={`expected-line-${line.id}`}
                          data-selected={selectedLine?.id === line.id}
                          className={selectedLine?.id === line.id ? 'bg-accent' : ''}
                        >
                          <TableCell>
                            <span className="font-mono text-[13px]">{line.itemDataNumber}</span>
                            {line.lotNumber && (
                              <span className="ml-2 text-xs text-muted-foreground">
                                Lot {line.lotNumber}
                              </span>
                            )}
                          </TableCell>
                          <TableCell>
                            <span className="font-mono text-[13px] text-muted-foreground">
                              {line.asnNumber}
                            </span>
                          </TableCell>
                          <TableCell className="text-right">
                            <span className="numeric">{formatAmount(line.remainingAmount)}</span>
                          </TableCell>
                          <TableCell className="text-right">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => selectLine(line)}
                              data-testid={`receive-against-${line.id}`}
                            >
                              Receive
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    All expected lines fully received.
                  </p>
                )
              ) : (
                <p className="text-sm text-muted-foreground">
                  Blind receipt — pick any product in the receive form.
                </p>
              )}
            </SectionCard>
          </div>

          {/* Right pane: receive-line form */}
          <SectionCard
            title={selectedLine ? `Receiving ${selectedLine.itemDataNumber}` : 'Receive a line'}
          >
            <ReceiveForm
              key={formKey}
              receiptId={receipt.id}
              prefill={{ asnLine: selectedLine }}
              disabled={!isOpen || isPaused}
              onReceived={handleReceived}
              receiptType={receipt.receiptType}
            />
          </SectionCard>
        </div>
      ) : (
        <div className="flex items-center gap-2 rounded-md border border-success bg-success/15 p-3 text-sm font-medium text-success-foreground">
          <PackageCheck className="size-4" />
          Receipt {humanizeStateName(receipt.stateName).toLowerCase()} — no further receiving.
        </div>
      )}

      {/* Received lines */}
      <SectionCard title="Received lines" noPad>
        {receipt.lines.length === 0 ? (
          <p className="p-5 text-sm text-muted-foreground">No lines received yet.</p>
        ) : (
          <Table data-testid="received-lines-table">
            <TableHeader>
              <TableRow>
                <TableHead>Product</TableHead>
                <TableHead className="text-right">Amount</TableHead>
                <TableHead>UL label</TableHead>
                <TableHead>Location</TableHead>
                <TableHead>Lock</TableHead>
                <TableHead>Stock unit</TableHead>
                <TableHead className="text-right">Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {receipt.lines.map((line) => (
                <TableRow
                  key={line.id}
                  data-testid={`received-line-${line.id}`}
                  className={line.reversed ? 'opacity-50' : undefined}
                >
                  <TableCell>
                    <span className="font-mono text-[13px]">{line.itemDataNumber}</span>
                    {line.lotNumber && (
                      <div className="text-xs text-muted-foreground">Lot {line.lotNumber}</div>
                    )}
                    {line.serialNumber && (
                      <div className="font-mono text-xs text-muted-foreground">
                        SN {line.serialNumber}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <span className="numeric">{formatAmount(line.amount)}</span>
                  </TableCell>
                  <TableCell>
                    <span className="font-mono text-[13px]">{line.unitLoadLabel}</span>
                  </TableCell>
                  <TableCell>{line.locationName}</TableCell>
                  <TableCell>
                    {line.lockType != null ? (
                      <span>
                        <StatusPill
                          label={RECEIPT_LOCK_LABELS[line.lockType] ?? `LOCK ${line.lockType}`}
                          tone="amber"
                        />
                        {line.note && (
                          <span className="ml-2 text-xs text-muted-foreground">{line.note}</span>
                        )}
                      </span>
                    ) : (
                      <span className="text-xs text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span className="font-mono text-[13px] text-muted-foreground">
                      #{line.stockUnitId}
                    </span>
                  </TableCell>
                  <TableCell className="text-right">
                    {line.reversed && (
                      <span data-testid={`wb-line-reversed-${line.id}`}>
                        <StatusPill label="Reversed" tone="grey" />
                      </span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </SectionCard>

      {/* Finish confirmation */}
      <AlertDialog open={confirmFinish} onOpenChange={setConfirmFinish}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Finish receipt?</AlertDialogTitle>
            <AlertDialogDescription>
              Unlocked stock moves to ON STOCK. Locked lines stay held for inspection.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep Open</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                finishReceipt.mutate(receipt.id, { onSuccess: () => setConfirmFinish(false) })
              }
              data-testid="finish-receipt-confirm"
            >
              Finish receipt
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
