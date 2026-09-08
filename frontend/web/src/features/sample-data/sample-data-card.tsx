/**
 * SampleDataCard -- the "Sample data" utility bar on Operations Control (`/`).
 *
 * Shows different states based on the sample-data phase:
 * - idle: "Load Sample Data" button (+ error message if the last load failed)
 * - loading: progress feedback with label and a Stop button
 * - loaded: success state with reset option
 *
 * Load and Reset are both destructive (Load's `POST /api/v1/demo/seed`
 * resets the demo warehouse first before regenerating it -- Task 9,
 * defect-burndown), so both go through an `AlertDialog` confirm step before
 * the underlying API call fires.
 *
 * Remounted 2026-07-21 (consolidation sprint, Task 3b): the June Control
 * redesign (232fb726) replaced the whole dashboard body with
 * `OperationsControl` and silently dropped this card in the process --
 * confirmed accidental (not an intentional retirement) by `git log -S
 * SampleDataCard`: a later commit (3815b1e5, 2026-07-13) repointed this very
 * component's data layer from the old per-entity seeder to the new
 * `karyo-demo` backend's `POST /api/v1/demo/seed`/`reset` endpoints without
 * ever noticing it wasn't mounted anywhere, and the ⌘K command palette
 * (`components/command/command-palette.tsx`) still offers "Load/Reset
 * sample data" as ungated commands that depend on this same
 * `SampleDataProvider` instance (wired in `AppShell`) -- so the seed/reset
 * affordance is still very much live product surface, just orphaned in the
 * DOM. Restyled to the bespoke rounded-2xl/bg-card bar idiom the rest of
 * this page uses (`kpi-strip.tsx`, `exceptions-card.tsx`) instead of the
 * retired shadcn `Card`/`CardHeader`/`CardContent`/`CardFooter` wrapper --
 * that component is otherwise unused across `src/pages` and `src/features`.
 */
import type { ReactNode } from 'react';
import { Database, Loader2, CheckCircle2, RotateCcw, Square } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { useSampleData } from '@/features/sample-data/use-sample-data';

export function SampleDataCard() {
  const { state, loadSampleData, resetSampleData, stopLoading } =
    useSampleData();

  if (state.phase === 'loading') {
    return (
      <Bar
        icon={<Loader2 className="size-4 animate-spin text-muted-foreground" />}
        title="Loading Sample Data"
        detail={state.progress.label || 'Generating backdated demo warehouse…'}
      >
        <Button variant="outline" size="sm" onClick={stopLoading}>
          <Square className="mr-1 size-3" />
          Stop
        </Button>
      </Bar>
    );
  }

  if (state.phase === 'resetting') {
    return (
      <Bar
        icon={<Loader2 className="size-4 animate-spin text-muted-foreground" />}
        title="Resetting Sample Data"
        detail="Removing the demo warehouse data…"
      />
    );
  }

  if (state.phase === 'loaded') {
    return (
      <Bar
        icon={<CheckCircle2 className="size-4 text-primary" />}
        title="Sample Data Loaded"
        detail={
          state.summary
            ? `${state.summary.locations} locations · ${state.summary.skus} SKUs · ${state.summary.orders} orders · ${state.summary.shipments} shipments`
            : 'The demo warehouse is ready — sign in as a manager to explore the seeded warehouse (zones, locations, products, stock), or reset to remove it.'
        }
      >
        <ResetButton onReset={resetSampleData} />
      </Bar>
    );
  }

  // phase === 'idle'
  return (
    <Bar
      icon={<Database className="size-4 text-muted-foreground" />}
      title="Sample Data"
      detail={
        state.error ??
        'Generate a full backdated demo warehouse — zones, locations, products, stock, and months of order/pick/ship/receipt history — to explore Karyo with realistic data.'
      }
      detailDanger={!!state.error}
    >
      <LoadButton onLoad={loadSampleData} />
      <ResetButton onReset={resetSampleData} />
    </Bar>
  );
}

// --- Shared bar shell (matches the rounded-2xl/bg-card idiom used by
// KpiStrip/ExceptionsCard on this page) ---

function Bar({
  icon,
  title,
  detail,
  detailDanger = false,
  children,
}: {
  icon: ReactNode;
  title: string;
  detail: string;
  detailDanger?: boolean;
  children?: ReactNode;
}) {
  return (
    <div
      data-testid="sample-data-card"
      className="mb-[var(--secmb,20px)] flex flex-col gap-3 rounded-2xl border border-border bg-card px-5 py-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex items-start gap-3">
        {icon}
        <div>
          <div className="text-[13px] font-semibold text-foreground">{title}</div>
          <p
            className={cn(
              'mt-0.5 max-w-prose text-[11.5px]',
              detailDanger ? 'text-destructive' : 'text-muted-foreground',
            )}
          >
            {detail}
          </p>
        </div>
      </div>
      {children && <div className="flex flex-none flex-wrap gap-2">{children}</div>}
    </div>
  );
}

// --- Load confirmation dialog ---
// Load is destructive: `POST /api/v1/demo/seed` resets the demo warehouse
// first so re-seeding is idempotent (see `DemoResource.seed`'s KDoc) --
// mirrors the Reset confirm below.

function LoadButton({ onLoad }: { onLoad: () => Promise<void> }) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button size="sm">
          <Database className="mr-2 size-3.5" />
          <span>Load Sample Data</span>
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Load sample data?</AlertDialogTitle>
          <AlertDialogDescription>
            This resets the demo warehouse first — all current data in this
            instance is replaced with a fresh 120-day history.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onLoad}>
            Load Sample Data
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

// --- Reset confirmation dialog ---

function ResetButton({
  onReset,
  label = 'Reset',
}: {
  onReset: () => Promise<void>;
  label?: string;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <RotateCcw className="mr-1 size-3" />
          {label}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Reset Sample Data?</AlertDialogTitle>
          <AlertDialogDescription>
            This will delete all sample data (zones, locations, products,
            stock). This action cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onReset}>
            Reset Sample Data
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
