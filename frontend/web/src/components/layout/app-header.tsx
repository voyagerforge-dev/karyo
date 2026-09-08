import { useEffect, useState } from 'react';
import { useLocation, Link } from 'react-router';
import { Bell, LogOut, Search, User, Shield, SlidersHorizontal } from 'lucide-react';
import { useAuth } from '@/components/auth/auth-provider';
import { useCommandPalette } from '@/components/command/command-palette-provider';
import { ModeToggle } from '@/components/theme/mode-toggle';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Separator } from '@/components/ui/separator';
import { Button } from '@/components/ui/button';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { navigationItems } from '@/config/navigation';
import { WorkspacePanel } from '@/components/layout/workspace-panel';

/**
 * Map route paths to human-readable labels for breadcrumbs.
 */
function getBreadcrumbs(pathname: string) {
  if (pathname === '/') {
    return [{ label: 'Operations Control', href: '/' }];
  }

  const segments = pathname.split('/').filter(Boolean);
  const breadcrumbs: Array<{ label: string; href: string }> = [
    { label: 'Warehouse', href: '/' },
  ];

  let currentPath = '';
  for (const segment of segments) {
    currentPath += `/${segment}`;
    const navItem = navigationItems.find((item) => item.url === currentPath);
    breadcrumbs.push({
      label: navItem?.title ?? segment.charAt(0).toUpperCase() + segment.slice(1),
      href: currentPath,
    });
  }

  return breadcrumbs;
}

/** Ticking HH:MM:SS clock (1s interval, cleared on unmount). */
function useClock(): string {
  const [time, setTime] = useState(() =>
    new Date().toLocaleTimeString([], { hour12: false }),
  );
  useEffect(() => {
    const id = setInterval(
      () => setTime(new Date().toLocaleTimeString([], { hour12: false })),
      1000,
    );
    return () => clearInterval(id);
  }, []);
  return time;
}

/**
 * Control-console topbar (64px): breadcrumb trail + tenant pill on the left;
 * ticking clock, command search, notifications, theme, and user on the right.
 */
export function AppHeader() {
  const location = useLocation();
  const { userName, tenantCode, logout } = useAuth();
  const { setOpen: setCommandOpen } = useCommandPalette();
  const breadcrumbs = getBreadcrumbs(location.pathname);
  const clock = useClock();
  const initials = userName ? userName.slice(0, 2).toUpperCase() : 'U';
  const [workspaceOpen, setWorkspaceOpen] = useState(false);

  return (
    <header className="flex h-16 shrink-0 items-center gap-3 border-b px-4 md:px-6">
      {/* Left: trigger + breadcrumbs + tenant pill */}
      <div className="flex min-w-0 items-center gap-3">
        <SidebarTrigger />
        <Separator orientation="vertical" className="h-4" />
        <Breadcrumb>
          <BreadcrumbList>
            {breadcrumbs.map((crumb, index) => {
              const isLast = index === breadcrumbs.length - 1;
              return (
                <BreadcrumbItem key={crumb.href}>
                  {index > 0 && <BreadcrumbSeparator />}
                  {isLast ? (
                    <BreadcrumbPage className="font-display text-[15px]">
                      {crumb.label}
                    </BreadcrumbPage>
                  ) : (
                    <BreadcrumbLink asChild>
                      <Link to={crumb.href}>{crumb.label}</Link>
                    </BreadcrumbLink>
                  )}
                </BreadcrumbItem>
              );
            })}
          </BreadcrumbList>
        </Breadcrumb>

        {/* Tenant selector pill */}
        <div className="ml-1 hidden items-center gap-2 rounded-full border bg-secondary px-3 py-1 sm:flex">
          <span className="h-2 w-2 rounded-full bg-[var(--acc-color,var(--primary))]" />
          <span className="text-[13px] font-medium">{tenantCode ?? 'Riverside DC'}</span>
        </div>
      </div>

      <div className="flex-1" />

      {/* Right: clock + actions */}
      <div className="flex items-center gap-1">
        <div className="mr-2 hidden items-center gap-2 sm:flex">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[var(--acc-color,var(--primary))] opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-[var(--acc-color,var(--primary))]" />
          </span>
          <span className="numeric text-[15px] font-medium tabular-nums">{clock}</span>
        </div>

        {/* ⌘K search pill */}
        <button
          type="button"
          data-testid="command-palette-trigger"
          onClick={() => setCommandOpen(true)}
          className="mr-2 hidden h-9 items-center gap-2 rounded-lg border bg-secondary px-3 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground md:flex"
        >
          <Search className="size-3.5" />
          <span>Search or command…</span>
          <kbd className="numeric rounded border bg-background px-1.5 text-[11px]">⌘K</kbd>
        </button>
        <Button
          variant="ghost"
          size="icon"
          className="md:hidden"
          onClick={() => setCommandOpen(true)}
        >
          <Search className="h-[1.2rem] w-[1.2rem]" />
          <span className="sr-only">Search or command</span>
        </Button>

        <ModeToggle />

        {/* Workspace settings */}
        <Button
          variant="ghost"
          size="icon"
          aria-label="Workspace settings"
          data-testid="workspace-gear"
          onClick={() => setWorkspaceOpen(true)}
        >
          <SlidersHorizontal className="h-[1.2rem] w-[1.2rem]" />
          <span className="sr-only">Workspace settings</span>
        </Button>

        {/* Notifications */}
        <Button variant="ghost" size="icon" className="relative">
          <Bell className="h-[1.2rem] w-[1.2rem]" />
          <span className="numeric absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-white">
            3
          </span>
          <span className="sr-only">Notifications</span>
        </Button>

        {/* User */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="ml-1 flex items-center gap-2.5 rounded-lg px-1.5 py-1 transition-colors hover:bg-accent"
            >
              <span className="flex h-[34px] w-[34px] items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
                {initials}
              </span>
              <div className="hidden flex-col items-start leading-tight lg:flex">
                <span className="text-[13px] font-medium">{userName ?? 'User'}</span>
                <span className="numeric text-[9.5px] uppercase tracking-[0.12em] text-muted-foreground">
                  {tenantCode ?? 'Floor Manager'}
                </span>
              </div>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel>
              <div className="flex flex-col space-y-1">
                <p className="text-sm font-medium">{userName ?? 'User'}</p>
                <p className="text-xs text-muted-foreground">
                  Tenant: {tenantCode ?? 'Unknown'}
                </p>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem disabled>
              <User className="mr-2 h-4 w-4" />
              Profile
            </DropdownMenuItem>
            <DropdownMenuItem asChild>
              <Link to="/admin/strategies">
                <Shield className="mr-2 h-4 w-4" />
                Admin
              </Link>
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={logout}>
              <LogOut className="mr-2 h-4 w-4" />
              Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <WorkspacePanel variant="console" open={workspaceOpen} onOpenChange={setWorkspaceOpen} />
    </header>
  );
}
