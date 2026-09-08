import { useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useOrderStrategies } from '@/pages/orders/use-orders';
import { useCreateWave, useSelectionRules } from '@/features/waves/use-waves';
import { WAVE_PICK_MODES, WAVE_SHORTAGE_ACTIONS } from '@/types/waves';

/** v1: a static two-key list. Any other key an order strategy might carry is out of scope for
 *  this dialog -- the strategy's own config is used unless one of these two is explicitly
 *  chosen here. */
const SELECTION_STRATEGY_KEYS = ['due-date-priority', 'rule-based'] as const;
type SelectionStrategyKey = (typeof SELECTION_STRATEGY_KEYS)[number];

interface CreateWaveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Parses a comma/whitespace-separated id list into distinct positive integers. */
function parseOrderIds(raw: string): number[] {
  return Array.from(
    new Set(
      raw
        .split(/[\s,]+/)
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
        .map(Number)
        .filter((n) => Number.isInteger(n) && n > 0),
    ),
  );
}

/**
 * New-wave dialog: choose the order strategy (drives the default pick mode/shortage action and,
 * when no explicit order ids are given, the eligible-order pool waved up to the strategy's
 * `waveMaxOrders`). Pick mode/shortage action left blank inherit the strategy's own default --
 * mirrors `CreateWaveRequest`, where both fields are optional.
 */
export function CreateWaveDialog({ open, onOpenChange }: CreateWaveDialogProps) {
  const { data: strategies, isLoading: strategiesLoading } = useOrderStrategies();
  const { data: rules } = useSelectionRules();
  const createMutation = useCreateWave();

  const [strategyId, setStrategyId] = useState('');
  const [orderIdsInput, setOrderIdsInput] = useState('');
  const [wavePickMode, setWavePickMode] = useState('');
  const [shortageAction, setShortageAction] = useState('');
  const [plannedReleaseAt, setPlannedReleaseAt] = useState('');
  const [selectionStrategy, setSelectionStrategy] = useState<SelectionStrategyKey | ''>('');
  const [selectionRuleId, setSelectionRuleId] = useState('');
  const [error, setError] = useState<string | null>(null);

  function reset() {
    setStrategyId('');
    setOrderIdsInput('');
    setWavePickMode('');
    setShortageAction('');
    setPlannedReleaseAt('');
    setSelectionStrategy('');
    setSelectionRuleId('');
    setError(null);
  }

  function handleOpenChange(next: boolean) {
    if (!next) reset();
    onOpenChange(next);
  }

  function handleSubmit() {
    if (!strategyId) {
      setError('An order strategy is required');
      return;
    }
    setError(null);
    const deliveryOrderIds = parseOrderIds(orderIdsInput);
    createMutation.mutate(
      {
        orderStrategyId: Number(strategyId),
        deliveryOrderIds: deliveryOrderIds.length > 0 ? deliveryOrderIds : undefined,
        wavePickMode: wavePickMode || undefined,
        shortageAction: shortageAction || undefined,
        plannedReleaseAt: plannedReleaseAt
          ? new Date(plannedReleaseAt).toISOString()
          : undefined,
        selectionStrategy: selectionStrategy || undefined,
        selectionRuleId:
          selectionStrategy === 'rule-based' && selectionRuleId ? Number(selectionRuleId) : undefined,
      },
      { onSuccess: () => handleOpenChange(false) },
    );
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent data-testid="create-wave-dialog">
        <DialogHeader>
          <DialogTitle>Create wave</DialogTitle>
          <DialogDescription>
            Group orders into a wave for priority allocation and batch picking.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label>Order strategy</Label>
            <Select value={strategyId} onValueChange={setStrategyId}>
              <SelectTrigger data-testid="wave-strategy-select">
                <SelectValue placeholder={strategiesLoading ? 'Loading...' : 'Select a strategy'} />
              </SelectTrigger>
              <SelectContent>
                {strategies?.map((s) => (
                  <SelectItem key={s.id} value={String(s.id)}>
                    {s.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <div className="space-y-2">
            <Label htmlFor="wave-order-ids">Orders (optional)</Label>
            <Input
              id="wave-order-ids"
              value={orderIdsInput}
              onChange={(e) => setOrderIdsInput(e.target.value)}
              placeholder="Order ids, comma-separated. Leave blank to wave the eligible pool."
              data-testid="wave-order-ids-input"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Pick mode</Label>
              <Select value={wavePickMode} onValueChange={setWavePickMode}>
                <SelectTrigger data-testid="wave-pick-mode-select">
                  <SelectValue placeholder="Strategy default" />
                </SelectTrigger>
                <SelectContent>
                  {WAVE_PICK_MODES.map((m) => (
                    <SelectItem key={m} value={m}>
                      {m}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Shortage action</Label>
              <Select value={shortageAction} onValueChange={setShortageAction}>
                <SelectTrigger data-testid="wave-shortage-action-select">
                  <SelectValue placeholder="Strategy default" />
                </SelectTrigger>
                <SelectContent>
                  {WAVE_SHORTAGE_ACTIONS.map((a) => (
                    <SelectItem key={a} value={a}>
                      {a}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2 rounded-lg border border-border p-3">
            <Label>Selection (optional -- overrides the strategy's own config)</Label>
            <Select
              value={selectionStrategy}
              onValueChange={(v) => {
                setSelectionStrategy(v as SelectionStrategyKey);
                if (v !== 'rule-based') setSelectionRuleId('');
              }}
            >
              <SelectTrigger data-testid="wave-selection-strategy-select">
                <SelectValue placeholder="Strategy default" />
              </SelectTrigger>
              <SelectContent>
                {SELECTION_STRATEGY_KEYS.map((k) => (
                  <SelectItem key={k} value={k}>
                    {k}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            {selectionStrategy === 'rule-based' && (
              <Select value={selectionRuleId} onValueChange={setSelectionRuleId}>
                <SelectTrigger data-testid="wave-selection-rule-select">
                  <SelectValue placeholder="Select a rule" />
                </SelectTrigger>
                <SelectContent>
                  {rules?.content.map((r) => (
                    <SelectItem key={r.id} value={String(r.id)}>
                      {r.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="wave-planned-release">Planned release (optional)</Label>
            <Input
              id="wave-planned-release"
              type="datetime-local"
              value={plannedReleaseAt}
              onChange={(e) => setPlannedReleaseAt(e.target.value)}
            />
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => handleOpenChange(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleSubmit}
              disabled={createMutation.isPending}
              data-testid="wave-create-submit"
            >
              {createMutation.isPending ? 'Creating...' : 'Create wave'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
