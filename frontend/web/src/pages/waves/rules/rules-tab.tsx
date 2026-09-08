import { useMemo, useState } from 'react';
import { ListChecks, Plus, Trash2 } from 'lucide-react';
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
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { useDeleteSelectionRule, useSelectionRules } from '@/features/waves/use-waves';
import { countConditions } from './rule-model';
import { RuleEditor } from './rule-editor';
import type { SelectionRuleResponse } from '@/types/waves';

interface RulesTabProps {
  canWrite: boolean;
}

function RuleRow({
  rule,
  active,
  onClick,
  onDelete,
  canWrite,
}: {
  rule: SelectionRuleResponse;
  active: boolean;
  onClick: () => void;
  onDelete: () => void;
  canWrite: boolean;
}) {
  const bound = rule.boundByStrategies > 0;
  return (
    <MasterListRow
      tone={bound ? 'violet' : 'grey'}
      active={active}
      onClick={onClick}
      testId={`rule-row-${rule.id}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-foreground">
          {rule.name}
        </span>
        {bound && (
          <Badge variant="secondary" data-testid={`rule-bound-badge-${rule.id}`}>
            Bound · {rule.boundByStrategies}
          </Badge>
        )}
      </div>
      <div className="mt-1.5 flex items-center justify-between text-[12.5px] text-foreground/70">
        <span>{countConditions(rule.definition)} conditions</span>
        {canWrite && (
          // A plain focusable span, not a nested <button> -- MasterListRow's root is itself a
          // <button>, and HTML forbids a <button> inside a <button>.
          <span
            role="button"
            tabIndex={bound ? -1 : 0}
            aria-disabled={bound}
            title={bound ? 'Delete blocked -- bound by an order strategy' : undefined}
            onClick={(e) => {
              e.stopPropagation();
              if (!bound) onDelete();
            }}
            onKeyDown={(e) => {
              if (!bound && (e.key === 'Enter' || e.key === ' ')) {
                e.preventDefault();
                e.stopPropagation();
                onDelete();
              }
            }}
            data-testid={`rule-delete-${rule.id}`}
            className={cn(
              'inline-flex size-6 items-center justify-center rounded-md',
              bound
                ? 'cursor-not-allowed opacity-40'
                : 'cursor-pointer text-muted-foreground hover:bg-accent hover:text-foreground',
            )}
          >
            <Trash2 className="size-3.5" />
          </span>
        )}
      </div>
    </MasterListRow>
  );
}

/**
 * RULES tab (selection-rules sprint, Task 5): master list of named `SelectionRule`s + the
 * registry-driven builder/preview in the detail pane. Lives inside the already-entitled
 * `WavesBoard` -- the license gate stays in `waves-page.tsx`, untouched.
 */
export function RulesTab({ canWrite }: RulesTabProps) {
  const { data, isLoading } = useSelectionRules();
  const deleteMutation = useDeleteSelectionRule();

  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<number | 'new' | undefined>();
  const [pendingDeleteId, setPendingDeleteId] = useState<number | undefined>();

  const rules = useMemo(() => {
    const q = search.trim().toLowerCase();
    const content = data?.content ?? [];
    return q ? content.filter((r) => r.name.toLowerCase().includes(q)) : content;
  }, [data, search]);

  const selectedRule =
    selectedId != null && selectedId !== 'new' ? rules.find((r) => r.id === selectedId) : undefined;

  function handleSaved(saved: SelectionRuleResponse) {
    setSelectedId(saved.id);
  }

  function confirmDelete() {
    if (pendingDeleteId == null) return;
    deleteMutation.mutate(pendingDeleteId, {
      onSuccess: () => {
        if (selectedId === pendingDeleteId) setSelectedId(undefined);
        setPendingDeleteId(undefined);
      },
      onError: () => setPendingDeleteId(undefined),
    });
  }

  return (
    <div className="space-y-4" data-testid="rules-tab">
      <div className="flex items-center justify-between">
        <h2 className="font-display text-lg font-bold tracking-[-0.02em]">Selection rules</h2>
        {canWrite && (
          <Button onClick={() => setSelectedId('new')} data-testid="new-rule-button">
            <Plus className="mr-2 size-4" />
            New rule
          </Button>
        )}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search rule name…"
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[68px] w-full rounded-xl" />
                <Skeleton className="h-[68px] w-full rounded-xl" />
              </>
            ) : rules.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No selection rules yet.
              </p>
            ) : (
              rules.map((r) => (
                <RuleRow
                  key={r.id}
                  rule={r}
                  active={r.id === selectedId}
                  onClick={() => setSelectedId(r.id)}
                  onDelete={() => setPendingDeleteId(r.id)}
                  canWrite={canWrite}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          selectedId === 'new' ? (
            <RuleEditor key="new" rule={null} canWrite={canWrite} onSaved={handleSaved} />
          ) : selectedRule ? (
            <RuleEditor key={selectedRule.id} rule={selectedRule} canWrite={canWrite} onSaved={handleSaved} />
          ) : (
            <DetailEmptyState
              icon={<ListChecks className="size-8 opacity-40" />}
              message="Select a rule, or create a new one"
            />
          )
        }
      />

      <AlertDialog open={pendingDeleteId != null} onOpenChange={(o) => !o && setPendingDeleteId(undefined)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this rule?</AlertDialogTitle>
            <AlertDialogDescription>
              This cannot be undone. A rule bound to an order strategy cannot be deleted.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep rule</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete} data-testid="rule-delete-confirm-btn">
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
