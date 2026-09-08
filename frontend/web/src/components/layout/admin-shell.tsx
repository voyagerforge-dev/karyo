import { useState } from 'react';
import { Outlet, useLocation, Link } from 'react-router';
import { Box, ChevronDown, AlertTriangle, SlidersHorizontal, ExternalLink } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/components/auth/auth-provider';
import { usePermissions } from '@/hooks/use-permissions';
import { useAdminPrefs, type AdminEnv } from '@/pages/admin/use-admin-prefs';
import { SPI_CATALOG } from '@/pages/admin/spi-catalog';
import { useExtensions } from '@/pages/admin/use-extensions';
import { WorkspacePanel } from '@/components/layout/workspace-panel';
import {
  visibleAdminNavItems,
  ADMIN_NAV_GROUP_ORDER,
  type AdminNavGroup,
  type AdminNavItem,
} from '@/config/admin-navigation';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

/** Violet chrome accent for the admin surface (lime stays the action color). */
const VIOLET = '#7C6CCF';

const ENVS: AdminEnv[] = ['Production', 'Staging', 'Sandbox'];

/**
 * Admin shell (v3) — a SEPARATE layout from the operator AppShell, with violet
 * chrome (brand, active nav). Renders the /admin/* surface. The env selector +
 * non-prod banner reflect the Workspace `env` pref (shared with the Admin
 * Workspace panel opened from the gear).
 */
export function AdminShell() {
  const location = useLocation();
  const { userName, logout } = useAuth();
  const { hasPermission } = usePermissions();
  const adminPrefs = useAdminPrefs();
  const { env, set } = adminPrefs;
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const initials = userName ? userName.slice(0, 2).toUpperCase() : 'AD';
  // Live SPI-registry count (B14) for the Extensions badge + footer; fall back
  // to the bundled static catalog when the endpoint is unavailable.
  const { data: extensions } = useExtensions();
  const spiCount = extensions?.length ?? SPI_CATALOG.length;

  // AdminShell itself is unguarded (router.tsx splits its children into two
  // nested AdminGuards -- user-admin and integration-admin -- so a manager
  // holding only integration-admin can still reach /admin/integrations).
  // The sidebar must therefore filter per-item, or that manager would see
  // (and be able to click into a redirect for) every other admin page too.
  const items = visibleAdminNavItems(hasPermission);
  const groups = ADMIN_NAV_GROUP_ORDER.map((group) => ({
    group,
    items: items.filter((i) => i.group === group),
  })).filter((g) => g.items.length > 0) as Array<{ group: AdminNavGroup; items: AdminNavItem[] }>;

  return (
    <div className="flex min-h-screen w-full bg-background text-foreground">
      {/* Sidebar */}
      <aside className="hidden w-[226px] flex-none flex-col border-r border-sidebar-border bg-sidebar md:flex">
        <div className="flex h-16 items-center gap-2.5 px-4">
          <div
            className="flex size-[34px] items-center justify-center rounded-[10px]"
            style={{ background: VIOLET }}
          >
            <Box className="size-[19px] text-signal-foreground" strokeWidth={2.2} />
          </div>
          <div className="flex flex-col leading-none">
            <span className="text-[18px] font-bold tracking-tight">karyo</span>
            <span className="numeric text-[9.5px] font-medium uppercase tracking-[0.18em] text-muted-foreground">
              Admin · IT
            </span>
          </div>
        </div>

        <nav className="flex-1 overflow-y-auto px-2 py-2">
          {groups.map(({ group, items }) => (
            <div key={group} className="mb-1">
              <div className="numeric px-3 py-2 text-[9.5px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/80">
                {group}
              </div>
              {items.map((item) => {
                const active = item.url && location.pathname.startsWith(item.url);
                // Extensions badge reflects the live registry count when loaded.
                const badge = item.url === '/admin/strategies' ? spiCount : item.badge;
                const content = (
                  <>
                    <item.icon className="size-4" />
                    <span className="flex-1">{item.title}</span>
                    {item.external && <ExternalLink className="size-3.5 text-muted-foreground/70" />}
                    {badge != null && (
                      <span className="numeric rounded-full bg-secondary px-1.5 py-0.5 text-[10.5px] font-semibold text-muted-foreground">
                        {badge}
                      </span>
                    )}
                  </>
                );
                const base =
                  'relative flex items-center gap-3 rounded-[9px] px-3 py-2 text-[13.5px]';
                if (!item.url) {
                  return (
                    <span
                      key={item.title}
                      title="Coming soon"
                      className={cn(base, 'cursor-default text-muted-foreground/50')}
                    >
                      {content}
                    </span>
                  );
                }
                if (item.external) {
                  return (
                    <a
                      key={item.title}
                      href={item.url}
                      target="_blank"
                      rel="noreferrer"
                      className={cn(
                        base,
                        'text-muted-foreground transition-colors hover:bg-accent hover:text-foreground',
                      )}
                    >
                      {content}
                    </a>
                  );
                }
                return (
                  <Link
                    key={item.title}
                    to={item.url}
                    className={cn(
                      base,
                      'text-muted-foreground transition-colors hover:bg-accent hover:text-foreground',
                    )}
                    style={
                      active
                        ? {
                            background: 'rgba(124,108,207,0.14)',
                            color: VIOLET,
                            fontWeight: 600,
                          }
                        : undefined
                    }
                  >
                    {active && (
                      <span
                        aria-hidden
                        className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r"
                        style={{ background: VIOLET }}
                      />
                    )}
                    {content}
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>

        <div className="border-t border-sidebar-border p-4">
          <div className="flex items-center gap-2">
            <span className="size-2 rounded-full" style={{ background: VIOLET }} />
            <span className="numeric text-[10.5px] font-semibold uppercase tracking-[0.12em]">
              SPI runtime · healthy
            </span>
          </div>
          <p className="mt-1.5 text-[11px] text-muted-foreground">
            {spiCount} extension seams registered
          </p>
        </div>
      </aside>

      {/* Main */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 flex-none items-center gap-3 border-b px-4 md:px-6">
          <Link to="/" className="text-sm text-muted-foreground hover:text-foreground">
            ← Console
          </Link>
          <span className="text-[15px] font-semibold">Admin</span>

          <div className="flex-1" />

          {/* Env selector */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="flex items-center gap-2 rounded-full border bg-secondary px-3 py-1.5 text-[13px] font-medium"
              >
                <span className="size-2 rounded-full" style={{ background: VIOLET }} />
                {env}
                <ChevronDown className="size-3.5 text-muted-foreground" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {ENVS.map((e) => (
                <DropdownMenuItem key={e} onClick={() => set('env', e)}>
                  {e}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          {/* Workspace settings */}
          <button
            type="button"
            aria-label="Workspace settings"
            data-testid="admin-workspace-gear"
            onClick={() => setWorkspaceOpen(true)}
            className="flex size-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <SlidersHorizontal className="size-[18px]" />
            <span className="sr-only">Workspace settings</span>
          </button>

          {/* User */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="flex items-center gap-2.5 rounded-lg px-1.5 py-1 hover:bg-accent">
                <span
                  className="flex size-[34px] items-center justify-center rounded-md text-xs font-bold text-signal-foreground"
                  style={{ background: VIOLET }}
                >
                  {initials}
                </span>
                <div className="hidden flex-col items-start leading-tight lg:flex">
                  <span className="text-[13px] font-medium">{userName ?? 'Admin'}</span>
                  <span className="numeric text-[9.5px] uppercase tracking-[0.12em] text-muted-foreground">
                    Platform Admin
                  </span>
                </div>
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
              <DropdownMenuItem asChild>
                <Link to="/">Back to Console</Link>
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={logout}>Logout</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </header>

        {/* Non-prod banner */}
        {env !== 'Production' && (
          <div
            className="flex items-center gap-2 border-b px-6 py-2 text-[12.5px]"
            style={{ background: 'rgba(124,108,207,0.10)', borderColor: 'rgba(124,108,207,0.3)' }}
          >
            <AlertTriangle className="size-4" style={{ color: VIOLET }} />
            <span className="text-muted-foreground">
              Editing the <span style={{ color: VIOLET }}>{env}</span> environment — changes won’t affect production until promoted.
            </span>
          </div>
        )}

        <main className="flex-1 overflow-auto p-4 md:p-6">
          <Outlet />
        </main>
      </div>

      <WorkspacePanel
        variant="admin"
        open={workspaceOpen}
        onOpenChange={setWorkspaceOpen}
        adminPrefs={adminPrefs}
      />
    </div>
  );
}
