import { useEffect } from 'react';
import { useLocation, Link } from 'react-router';
import { Box } from 'lucide-react';
import { usePermissions } from '@/hooks/use-permissions';
import { getGroupedNavItems, type NavBadgeTone } from '@/config/navigation';
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
  useSidebar,
} from '@/components/ui/sidebar';
import { cn } from '@/lib/utils';

/** Count-badge colors by semantic tone (Control palette). */
function badgeClass(tone: NavBadgeTone): string {
  switch (tone) {
    case 'signal':
      return 'bg-[var(--acc-soft)] text-[var(--acc-color)]';
    case 'warning':
      return 'bg-warning text-warning-foreground';
    default:
      return 'bg-secondary text-muted-foreground';
  }
}

/**
 * Control-console sidebar: brand header, grouped + permission-filtered nav with
 * count badges and lime active state. Collapses to icons at the tablet
 * breakpoint. (No footer — the prior "Day shift · Live / 42 crew on floor"
 * block was fabricated data with no backend source and was removed.)
 */
export function AppSidebar() {
  const location = useLocation();
  const { permissions } = usePermissions();
  const { setOpen } = useSidebar();

  const groups = getGroupedNavItems(permissions);

  // Auto-collapse sidebar at tablet breakpoint (768px)
  useEffect(() => {
    const mediaQuery = window.matchMedia('(max-width: 768px)');
    const handleChange = (e: MediaQueryListEvent | MediaQueryList) => {
      if (e.matches) setOpen(false);
    };
    handleChange(mediaQuery);
    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, [setOpen]);

  return (
    <Sidebar collapsible="icon">
      {/* Brand header */}
      <SidebarHeader className="h-16 justify-center px-4">
        <Link to="/" className="flex items-center gap-2.5">
          <div className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[10px] bg-primary text-primary-foreground">
            <Box className="h-[19px] w-[19px]" strokeWidth={2.2} />
          </div>
          <div className="flex flex-col leading-none group-data-[collapsible=icon]:hidden">
            <span className="text-[18px] font-bold tracking-tight text-foreground">
              karyo
            </span>
            <span className="numeric text-[9.5px] font-medium uppercase tracking-[0.18em] text-muted-foreground">
              Control
            </span>
          </div>
        </Link>
      </SidebarHeader>

      <SidebarContent className="gap-0">
        {groups.map(({ group, items }) => (
          <SidebarGroup key={group}>
            <SidebarGroupLabel className="numeric text-[9.5px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/80">
              {group}
            </SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {items.map((item) => {
                  const isActive =
                    item.url === '/'
                      ? location.pathname === '/'
                      : location.pathname.startsWith(item.url);

                  return (
                    <SidebarMenuItem key={item.url}>
                      <SidebarMenuButton
                        asChild
                        isActive={isActive}
                        tooltip={item.title}
                        className={cn(
                          'relative gap-3 text-[13.5px] data-[active=true]:bg-[var(--acc-deep)] data-[active=true]:font-semibold data-[active=true]:text-[var(--acc-color)]',
                          isActive &&
                            'before:absolute before:left-0 before:top-1/2 before:h-5 before:w-[3px] before:-translate-y-1/2 before:rounded-r before:bg-[var(--acc-color)]',
                        )}
                      >
                        <Link to={item.url}>
                          <item.icon className="size-4" />
                          <span>{item.title}</span>
                          {item.badge && (
                            <span
                              className={cn(
                                'numeric ml-auto rounded-full px-1.5 py-0.5 text-[10.5px] font-semibold leading-none tabular-nums group-data-[collapsible=icon]:hidden',
                                badgeClass(item.badge.tone),
                              )}
                            >
                              {item.badge.count}
                            </span>
                          )}
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  );
                })}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarRail />
    </Sidebar>
  );
}
