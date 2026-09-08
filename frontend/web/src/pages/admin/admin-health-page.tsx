import { CheckCircle2, XCircle, AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useHealth } from '@/pages/admin/use-health';

const UP = { color: '#7FB77E', bg: 'rgba(127,183,126,0.14)' };
const DOWN = { color: '#E06A5A', bg: 'rgba(224,106,90,0.16)' };

/**
 * Admin -> Health (B17): the app's own SmallRye Health report
 * (`GET /q/health`, unauthenticated, see `use-health.ts` for why it bypasses
 * the `api` client). Real backend, no mock data — an unreachable endpoint is
 * an honest error state, not a fabricated "all systems operational".
 */
export function AdminHealthPage() {
  const { data, isLoading, isError } = useHealth();

  return (
    <div data-testid="admin-health-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Health
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Live readiness/liveness checks from the running app (SmallRye Health,{' '}
          <code className="numeric">/q/health</code>).
        </p>
      </div>

      {isLoading && (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          Loading health…
        </div>
      )}

      {!isLoading && isError && (
        <div
          data-testid="admin-health-error"
          className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-8 py-12 text-center"
        >
          <AlertTriangle className="size-8" style={{ color: DOWN.color }} />
          <div>
            <p className="text-[14px] font-semibold text-foreground">
              Couldn&apos;t reach the health endpoint
            </p>
            <p className="mt-1 text-[12.5px] text-muted-foreground">
              <code className="numeric">/q/health</code> and{' '}
              <code className="numeric">/q/health/ready</code> both failed — this is likely a
              proxy/routing gap, not necessarily an app outage.
            </p>
          </div>
        </div>
      )}

      {!isLoading && !isError && data && (
        <>
          <div className="mb-5 flex items-center gap-3 rounded-2xl border border-border bg-card px-5 py-4">
            <StatusBadge status={data.status} large />
            <div>
              <div className="text-[14px] font-semibold text-foreground">
                Overall status: {data.status}
              </div>
              <div className="numeric mt-0.5 text-[11px] text-muted-foreground/70">
                {data.checks.length} check{data.checks.length === 1 ? '' : 's'}
              </div>
            </div>
          </div>

          {data.checks.length === 0 ? (
            <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
              No health checks reported.
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {data.checks.map((check) => (
                <div
                  key={check.name}
                  className="flex items-center justify-between gap-3 rounded-xl border border-border bg-card px-[15px] py-3"
                >
                  <div className="flex items-center gap-3">
                    <StatusBadge status={check.status} />
                    <span className="text-[13.5px] font-medium text-foreground">{check.name}</span>
                  </div>
                  {check.data && Object.keys(check.data).length > 0 && (
                    <span className="numeric max-w-[50%] truncate text-[11px] text-muted-foreground/70">
                      {Object.entries(check.data)
                        .map(([k, v]) => `${k}=${String(v)}`)
                        .join(' · ')}
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function StatusBadge({ status, large = false }: { status: 'UP' | 'DOWN'; large?: boolean }) {
  const style = status === 'UP' ? UP : DOWN;
  return (
    <span
      className={cn(
        'flex flex-none items-center gap-1.5 rounded-full font-bold',
        large ? 'px-3 py-1.5 text-[12.5px]' : 'px-2 py-0.5 text-[10.5px]',
      )}
      style={{ color: style.color, background: style.bg }}
    >
      {status === 'UP' ? (
        <CheckCircle2 className={large ? 'size-4' : 'size-3'} />
      ) : (
        <XCircle className={large ? 'size-4' : 'size-3'} />
      )}
      {status}
    </span>
  );
}
