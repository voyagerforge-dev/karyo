import { useState, type ReactNode } from 'react';
import { Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { ApiError } from '@/lib/api-client';
import {
  useCreateSelectionRule,
  usePreviewSelectionRule,
  useSelectionRuleFields,
  useUpdateSelectionRule,
} from '@/features/waves/use-waves';
import type {
  SelectionCondition,
  SelectionConditionValue,
  SelectionFieldResponse,
  SelectionFieldType,
  SelectionRule,
  SelectionRuleResponse,
} from '@/types/waves';
import {
  MAX_CONDITIONS,
  allConditionsComplete,
  countConditions,
  datetimeLocalToInstant,
  emptyCondition,
  emptyRule,
  formatRelativeOffset,
  instantToDatetimeLocal,
  opRequiresArray,
  opRequiresNoValue,
  opRequiresRange,
  parseRelativeOffset,
} from './rule-model';

interface RuleEditorProps {
  /** null = a new, unsaved rule. */
  rule: SelectionRuleResponse | null;
  canWrite: boolean;
  onSaved?: (rule: SelectionRuleResponse) => void;
}

/**
 * Registry-driven selection-rule builder + preview (selection-rules sprint, Task 5). Field/op
 * choices come entirely from `GET /fields` -- no field name or op set is hardcoded here. Client
 * caps (20 conditions, depth 2, "add group" hidden inside a group) mirror
 * `com.karyo.wave.rule.RuleValidator` as a UX guardrail; the server re-validates on save and its
 * 422 messages are surfaced here, split per violation, in addition to the global toast.
 */
export function RuleEditor({ rule, canWrite, onSaved }: RuleEditorProps) {
  const { data: fields = [] } = useSelectionRuleFields();
  const createMutation = useCreateSelectionRule();
  const updateMutation = useUpdateSelectionRule();
  const previewMutation = usePreviewSelectionRule();

  const [name, setName] = useState(rule?.name ?? '');
  const [description, setDescription] = useState(rule?.description ?? '');
  const [definition, setDefinition] = useState<SelectionRule>(rule?.definition ?? emptyRule());
  const [saveErrors, setSaveErrors] = useState<string[]>([]);

  const isSaving = createMutation.isPending || updateMutation.isPending;
  const total = countConditions(definition);

  async function handleSave() {
    const trimmedName = name.trim();
    const errors: string[] = [];
    if (!trimmedName) errors.push('Name is required');
    if (total === 0) errors.push('Rule must contain at least one condition');
    if (total > MAX_CONDITIONS) errors.push(`Too many conditions (max ${MAX_CONDITIONS})`);
    if (fields.length > 0 && !allConditionsComplete(definition, fields)) {
      errors.push('Every condition needs a field, an operator, and a value');
    }
    if (errors.length > 0) {
      setSaveErrors(errors);
      return;
    }
    setSaveErrors([]);

    const body = { name: trimmedName, description: description.trim() || undefined, definition };
    try {
      const saved = rule
        ? await updateMutation.mutateAsync({ id: rule.id, body })
        : await createMutation.mutateAsync(body);
      onSaved?.(saved);
    } catch (err) {
      if (err instanceof ApiError) {
        setSaveErrors(
          err.problem.detail
            .split(';')
            .map((m) => m.trim())
            .filter((m) => m.length > 0),
        );
      } else {
        setSaveErrors(['Save failed']);
      }
    }
  }

  function handlePreview() {
    if (rule) previewMutation.mutate(rule.id);
  }

  return (
    <div className="space-y-4" data-testid="rule-editor">
      <div className="space-y-2">
        <Label htmlFor="rule-name">Name</Label>
        <Input
          id="rule-name"
          value={name}
          disabled={!canWrite}
          onChange={(e) => setName(e.target.value)}
          data-testid="rule-name-input"
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="rule-description">Description (optional)</Label>
        <Textarea
          id="rule-description"
          value={description ?? ''}
          disabled={!canWrite}
          onChange={(e) => setDescription(e.target.value)}
          data-testid="rule-description-input"
        />
      </div>

      <RuleLevelEditor
        node={definition}
        onChange={setDefinition}
        depth={0}
        fields={fields}
        totalConditions={total}
        canWrite={canWrite}
        testIdPrefix="rule"
      />

      {saveErrors.length > 0 && (
        <ul
          data-testid="rule-save-errors"
          className="list-disc space-y-1 rounded-lg border border-destructive/40 bg-destructive/5 p-3 pl-6 text-[12.5px] text-destructive"
        >
          {saveErrors.map((message, i) => (
            <li key={i}>{message}</li>
          ))}
        </ul>
      )}

      <div className="flex items-center justify-between gap-2 pt-2">
        {canWrite ? (
          <Button onClick={handleSave} disabled={isSaving} data-testid="rule-save-button">
            {isSaving ? 'Saving…' : 'Save rule'}
          </Button>
        ) : (
          <span />
        )}
        <Button
          type="button"
          variant="outline"
          onClick={handlePreview}
          disabled={!rule || previewMutation.isPending}
          title={rule ? undefined : 'Save the rule first to preview its matches'}
          data-testid="rule-preview-button"
        >
          {previewMutation.isPending ? 'Previewing…' : 'Preview'}
        </Button>
      </div>

      {previewMutation.data && (
        <div
          data-testid="rule-preview-result"
          className="rounded-lg border border-border bg-card p-3 text-[12.5px]"
        >
          <p className="font-semibold text-foreground">
            {previewMutation.data.matchedCount} of {previewMutation.data.poolSize} eligible orders
            matched
          </p>
          {previewMutation.data.sample.length > 0 && (
            <ul className="mt-2 space-y-1 text-foreground/70">
              {previewMutation.data.sample.slice(0, 10).map((o) => (
                <li key={o.orderId}>
                  {o.orderNumber} · {o.customerName ?? '—'} · {o.deliveryDate ?? '—'}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

/** One combinator level -- the top-level rule (depth 0) or one nested group (depth 1). "Add
 *  group" only renders at depth 0 -- the depth-cap mirror ("no add group inside a group"). */
function RuleLevelEditor({
  node,
  onChange,
  onRemoveSelf,
  depth,
  fields,
  totalConditions,
  canWrite,
  testIdPrefix,
}: {
  node: SelectionRule;
  onChange: (updated: SelectionRule) => void;
  onRemoveSelf?: () => void;
  depth: number;
  fields: SelectionFieldResponse[];
  totalConditions: number;
  canWrite: boolean;
  testIdPrefix: string;
}) {
  const atCap = totalConditions >= MAX_CONDITIONS;

  function updateCondition(i: number, updated: SelectionCondition) {
    onChange({ ...node, conditions: node.conditions.map((c, idx) => (idx === i ? updated : c)) });
  }
  function removeCondition(i: number) {
    onChange({ ...node, conditions: node.conditions.filter((_, idx) => idx !== i) });
  }
  function addCondition() {
    onChange({ ...node, conditions: [...node.conditions, emptyCondition(fields[0]?.field ?? '')] });
  }
  function addGroup() {
    onChange({ ...node, groups: [...node.groups, emptyRule()] });
  }
  function updateGroupAt(i: number, updated: SelectionRule) {
    onChange({ ...node, groups: node.groups.map((g, idx) => (idx === i ? updated : g)) });
  }
  function removeGroupAt(i: number) {
    onChange({ ...node, groups: node.groups.filter((_, idx) => idx !== i) });
  }

  return (
    <div
      className={cn('space-y-3', depth > 0 && 'rounded-lg border border-border p-3')}
      data-testid={`${testIdPrefix}-level`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1">
          {(['AND', 'OR'] as const).map((c) => (
            <button
              key={c}
              type="button"
              disabled={!canWrite}
              onClick={() => onChange({ ...node, combinator: c })}
              data-testid={`${testIdPrefix}-combinator-${c}`}
              aria-pressed={node.combinator === c}
              className={cn(
                'rounded-md px-2.5 py-1 text-[12px] font-semibold transition-colors',
                node.combinator === c
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-card text-muted-foreground hover:text-foreground',
              )}
            >
              {c}
            </button>
          ))}
        </div>
        {depth > 0 && canWrite && onRemoveSelf && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onRemoveSelf}
            data-testid={`${testIdPrefix}-remove-group`}
          >
            <X className="mr-1 size-3.5" /> Remove group
          </Button>
        )}
      </div>

      <div className="space-y-2">
        {node.conditions.map((condition, i) => (
          <ConditionRow
            key={i}
            condition={condition}
            fields={fields}
            canWrite={canWrite}
            testIdPrefix={`${testIdPrefix}-condition-${i}`}
            onChange={(updated) => updateCondition(i, updated)}
            onRemove={() => removeCondition(i)}
          />
        ))}
      </div>

      {canWrite && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={addCondition}
          disabled={atCap}
          title={atCap ? `Maximum ${MAX_CONDITIONS} conditions reached` : undefined}
          data-testid={`${testIdPrefix}-add-condition`}
        >
          <Plus className="mr-1 size-3.5" /> Add condition
        </Button>
      )}

      {depth === 0 && (
        <div className="space-y-3 border-t border-border pt-3">
          {node.groups.map((group, i) => (
            <RuleLevelEditor
              key={i}
              node={group}
              onChange={(updated) => updateGroupAt(i, updated)}
              onRemoveSelf={() => removeGroupAt(i)}
              depth={depth + 1}
              fields={fields}
              totalConditions={totalConditions}
              canWrite={canWrite}
              testIdPrefix={`${testIdPrefix}-group-${i}`}
            />
          ))}
          {canWrite && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={addGroup}
              data-testid={`${testIdPrefix}-add-group`}
            >
              <Plus className="mr-1 size-3.5" /> Add group
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function ConditionRow({
  condition,
  fields,
  canWrite,
  testIdPrefix,
  onChange,
  onRemove,
}: {
  condition: SelectionCondition;
  fields: SelectionFieldResponse[];
  canWrite: boolean;
  testIdPrefix: string;
  onChange: (c: SelectionCondition) => void;
  onRemove: () => void;
}) {
  const field = fields.find((f) => f.field === condition.field);
  const ops = field?.ops ?? [];

  return (
    <div
      className="flex flex-wrap items-center gap-2 rounded-lg border border-border p-2.5"
      data-testid={testIdPrefix}
    >
      <Select
        value={condition.field}
        onValueChange={(next) => onChange({ field: next, op: '', value: undefined })}
        disabled={!canWrite}
      >
        <SelectTrigger data-testid={`${testIdPrefix}-field`} className="min-w-[150px]">
          <SelectValue placeholder="Field" />
        </SelectTrigger>
        <SelectContent>
          {fields.map((f) => (
            <SelectItem key={f.field} value={f.field}>
              {f.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Select
        value={condition.op}
        onValueChange={(next) => onChange({ ...condition, op: next, value: undefined })}
        disabled={!canWrite || !field}
      >
        <SelectTrigger data-testid={`${testIdPrefix}-op`} className="min-w-[110px]">
          <SelectValue placeholder="Op" />
        </SelectTrigger>
        <SelectContent>
          {ops.map((op) => (
            <SelectItem key={op} value={op}>
              {op}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {field && condition.op && !opRequiresNoValue(condition.op) && (
        <ConditionValueInput
          field={field}
          op={condition.op}
          value={condition.value}
          canWrite={canWrite}
          onChange={(value) => onChange({ ...condition, value })}
          testIdPrefix={`${testIdPrefix}-value`}
        />
      )}

      {canWrite && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onRemove}
          aria-label="Remove condition"
          data-testid={`${testIdPrefix}-remove`}
        >
          <X className="size-3.5" />
        </Button>
      )}
    </div>
  );
}

function ConditionValueInput({
  field,
  op,
  value,
  canWrite,
  onChange,
  testIdPrefix,
}: {
  field: SelectionFieldResponse;
  op: string;
  value: SelectionConditionValue | undefined;
  canWrite: boolean;
  onChange: (v: SelectionConditionValue) => void;
  testIdPrefix: string;
}) {
  if (opRequiresArray(op)) {
    const values = Array.isArray(value) ? value.map(String) : [];
    return (
      <ChipsInput
        value={values}
        onChange={onChange}
        canWrite={canWrite}
        testId={`${testIdPrefix}-chips`}
      />
    );
  }

  if (opRequiresRange(op)) {
    const [lo, hi] = Array.isArray(value) ? value : ['', ''];
    return (
      <div className="flex items-center gap-1.5">
        <ScalarInput
          type={field.type}
          value={lo}
          canWrite={canWrite}
          onChange={(v) => onChange([v ?? '', hi ?? ''])}
          testId={`${testIdPrefix}-lo`}
        />
        <span className="text-[11.5px] text-muted-foreground">and</span>
        <ScalarInput
          type={field.type}
          value={hi}
          canWrite={canWrite}
          onChange={(v) => onChange([lo ?? '', v ?? ''])}
          testId={`${testIdPrefix}-hi`}
        />
      </div>
    );
  }

  return (
    <ScalarInput
      type={field.type}
      value={typeof value === 'string' || typeof value === 'number' ? value : undefined}
      canWrite={canWrite}
      onChange={onChange}
      testId={testIdPrefix}
      allowRelative={field.type === 'DATE'}
    />
  );
}

/** Comma/Enter-separated chip input for the `in` op -- values are plain strings; a future
 *  NUMBER-typed `in` field would need numeric coercion here, out of scope for the current
 *  STRING-only registry. */
function ChipsInput({
  value,
  onChange,
  canWrite,
  testId,
}: {
  value: string[];
  onChange: (v: string[]) => void;
  canWrite: boolean;
  testId: string;
}) {
  const [draft, setDraft] = useState('');

  function commit() {
    const v = draft.trim();
    if (v && !value.includes(v)) onChange([...value, v]);
    setDraft('');
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5" data-testid={testId}>
      {value.map((chip) => (
        <Badge key={chip} variant="secondary" className="gap-1">
          {chip}
          {canWrite && (
            <button
              type="button"
              onClick={() => onChange(value.filter((v) => v !== chip))}
              aria-label={`Remove ${chip}`}
            >
              <X className="size-3" />
            </button>
          )}
        </Badge>
      ))}
      {canWrite && (
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ',') {
              e.preventDefault();
              commit();
            }
          }}
          onBlur={commit}
          placeholder="Value, Enter or comma"
          className="h-7 w-36 text-xs"
          data-testid={`${testId}-input`}
        />
      )}
    </div>
  );
}

/** Scalar value control for one field type. DATE conditions (non-range) get a "relative" toggle
 *  producing a `TODAY+N`/`TODAY-N` literal instead of a fixed calendar date. */
function ScalarInput({
  type,
  value,
  canWrite,
  onChange,
  testId,
  allowRelative,
}: {
  type: SelectionFieldType;
  value: string | number | undefined;
  canWrite: boolean;
  onChange: (v: string | number | undefined) => void;
  testId: string;
  allowRelative?: boolean;
}): ReactNode {
  const relativeOffset = allowRelative ? parseRelativeOffset(value) : null;
  const [relative, setRelative] = useState(relativeOffset !== null);

  function toggleRelative(next: boolean) {
    setRelative(next);
    onChange(next ? formatRelativeOffset(0) : undefined);
  }

  if (type === 'NUMBER') {
    return (
      <Input
        type="number"
        value={value ?? ''}
        disabled={!canWrite}
        onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
        data-testid={testId}
        className="w-28"
      />
    );
  }

  if (allowRelative) {
    return (
      <div className="flex items-center gap-1.5">
        <Switch
          checked={relative}
          onCheckedChange={toggleRelative}
          disabled={!canWrite}
          data-testid={`${testId}-relative-toggle`}
        />
        <span className="text-[11px] text-muted-foreground">Relative</span>
        {relative ? (
          <>
            <span className="text-[11.5px] text-muted-foreground">TODAY</span>
            <Input
              type="number"
              value={relativeOffset ?? 0}
              disabled={!canWrite}
              onChange={(e) => onChange(formatRelativeOffset(Number(e.target.value)))}
              data-testid={`${testId}-relative-offset`}
              className="w-20"
            />
          </>
        ) : (
          <Input
            type="date"
            value={typeof value === 'string' ? value : ''}
            disabled={!canWrite}
            onChange={(e) => onChange(e.target.value)}
            data-testid={testId}
            className="w-40"
          />
        )}
      </div>
    );
  }

  if (type === 'DATE') {
    return (
      <Input
        type="date"
        value={typeof value === 'string' ? value : ''}
        disabled={!canWrite}
        onChange={(e) => onChange(e.target.value)}
        data-testid={testId}
        className="w-52"
      />
    );
  }

  if (type === 'DATETIME') {
    // A `datetime-local` input emits an offset-less local wall-clock string ("2026-08-21T00:00"),
    // which `TodayLiteral.parseInstant` can never parse -- convert to/from a strict Instant
    // ("...Z") at the edges so the condition value is always something the server can read.
    return (
      <Input
        type="datetime-local"
        value={instantToDatetimeLocal(value)}
        disabled={!canWrite}
        onChange={(e) => onChange(datetimeLocalToInstant(e.target.value))}
        data-testid={testId}
        className="w-52"
      />
    );
  }

  return (
    <Input
      type="text"
      value={typeof value === 'string' ? value : ''}
      disabled={!canWrite}
      onChange={(e) => onChange(e.target.value)}
      data-testid={testId}
      className="w-40"
    />
  );
}
