/**
 * ⌘K command palette -- the future copilot front door (v1.6).
 *
 * v1.2.0 scope: page navigation (role-aware, same visibility as the
 * sidebar), create actions, sample-data actions, dark-mode toggle, and a
 * debounced product quick-search over the existing products API.
 */
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowDownUp,
  ArrowRightLeft,
  ClipboardCheck,
  ClipboardPlus,
  Database,
  ListChecks,
  MapPin,
  PackageOpen,
  Moon,
  Package,
  PackageCheck,
  PackagePlus,
  RotateCcw,
  Send,
  SlidersHorizontal,
  Sparkles,
  Sun,
  TruckIcon,
  UserPlus,
} from 'lucide-react';
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command';
import { useCommandPalette } from '@/components/command/command-palette-provider';
import { useTheme } from '@/components/theme/theme-provider';
import { usePermissions } from '@/hooks/use-permissions';
import { useDemoEnabled } from '@/features/sample-data/use-demo-enabled';
import { getVisibleNavItems } from '@/config/navigation';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { ProductResponse } from '@/types/product';

/** Debounce a changing value (for as-you-type entity search). */
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export function CommandPalette() {
  const { open, setOpen } = useCommandPalette();
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const { permissions, hasPermission } = usePermissions();
  const demoEnabled = useDemoEnabled();

  const [query, setQuery] = useState('');
  const debouncedQuery = useDebouncedValue(query, 250);

  // Reset the input whenever the palette closes.
  const handleOpenChange = (nextOpen: boolean) => {
    setOpen(nextOpen);
    if (!nextOpen) setQuery('');
  };

  const pages = useMemo(
    () => getVisibleNavItems(permissions),
    [permissions],
  );

  // --- Product quick-search (debounced; existing products API) ---
  const searchTerm = debouncedQuery.trim().toLowerCase();
  const searchEnabled =
    open && searchTerm.length >= 2 && hasPermission('product-read');
  const { data: productData } = useQuery({
    queryKey: ['products', 'palette-search'],
    queryFn: () =>
      api.get<PaginatedResponse<ProductResponse>>(
        '/api/v1/products?page=0&size=100',
      ),
    enabled: searchEnabled,
    staleTime: 30_000,
  });
  const productMatches = useMemo(() => {
    if (!searchEnabled || !productData?.content) return [];
    return productData.content
      .filter(
        (p) =>
          p.name.toLowerCase().includes(searchTerm) ||
          p.number.toLowerCase().includes(searchTerm),
      )
      .slice(0, 5);
  }, [searchEnabled, productData, searchTerm]);

  /** Close the palette, then run the command. */
  const run = (command: () => void) => {
    handleOpenChange(false);
    command();
  };

  const toggleDarkMode = () => {
    const isDark = document.documentElement.classList.contains('dark');
    setTheme(isDark ? 'light' : 'dark');
  };

  return (
    <CommandDialog
      open={open}
      onOpenChange={handleOpenChange}
      title="Command palette"
      description="Search pages, run actions, or find products"
    >
      <CommandInput
        placeholder="Search or command…"
        value={query}
        onValueChange={setQuery}
      />
      <CommandList>
        <CommandEmpty>No results found.</CommandEmpty>

        <CommandGroup heading="Pages">
          {pages.map((page) => (
            <CommandItem
              key={page.url}
              value={`go to ${page.title}`}
              onSelect={() => run(() => navigate(page.url))}
            >
              <page.icon />
              {page.title}
            </CommandItem>
          ))}
        </CommandGroup>

        <CommandSeparator />

        <CommandGroup heading="Actions">
          {hasPermission('order-write') && (
            <CommandItem
              value="create order"
              onSelect={() => run(() => navigate('/orders?create=1'))}
            >
              <ClipboardPlus />
              Create order
            </CommandItem>
          )}
          {hasPermission('order-write') && (
            <CommandItem
              value="create asn advance shipping notice"
              onSelect={() => run(() => navigate('/asns?create=1'))}
            >
              <TruckIcon />
              Create ASN
            </CommandItem>
          )}
          {hasPermission('order-write') && (
            <CommandItem
              value="new receipt goods receiving"
              onSelect={() => run(() => navigate('/receiving?create=1'))}
            >
              <PackageCheck />
              New receipt
            </CommandItem>
          )}
          {hasPermission('task-write') && (
            <CommandItem
              value="create move transport task putaway"
              onSelect={() => run(() => navigate('/tasks?create=1'))}
            >
              <ArrowRightLeft />
              Create move
            </CommandItem>
          )}
          {hasPermission('fulfillment-read') && (
            <CommandItem
              value="picking pick orders queue fulfillment"
              onSelect={() => run(() => navigate('/tasks?type=PICK'))}
            >
              <ListChecks />
              Go to Picks
            </CommandItem>
          )}
          {hasPermission('fulfillment-read') && (
            <CommandItem
              value="packing pack shipments station fulfillment"
              onSelect={() => run(() => navigate('/packing'))}
            >
              <PackageOpen />
              Go to Packing
            </CommandItem>
          )}
          {hasPermission('fulfillment-read') && (
            <CommandItem
              value="shipments ship dispatch manifest documents fulfillment"
              onSelect={() => run(() => navigate('/shipments'))}
            >
              <Send />
              Go to Shipments
            </CommandItem>
          )}
          {hasPermission('task-read') && (
            <CommandItem
              value="replenishment restock refill fixed location needs scan"
              onSelect={() => run(() => navigate('/tasks?type=REPLENISH'))}
            >
              <ArrowDownUp />
              Go to Replenishment
            </CommandItem>
          )}
          {hasPermission('inventory-read') && (
            <CommandItem
              value="cycle count stocktaking inventory count session blind"
              onSelect={() => run(() => navigate('/cycle-count'))}
            >
              <ClipboardCheck />
              Go to Cycle Count
            </CommandItem>
          )}
          {hasPermission('product-write') && (
            <CommandItem
              value="create product"
              onSelect={() => run(() => navigate('/items?create=1'))}
            >
              <PackagePlus />
              Create product
            </CommandItem>
          )}
          {hasPermission('layout-write') && (
            <CommandItem
              value="create location"
              onSelect={() => run(() => navigate('/locations'))}
            >
              <MapPin />
              Create location
            </CommandItem>
          )}
          {hasPermission('user-admin') && (
            <CommandItem
              value="create user"
              onSelect={() => run(() => navigate('/users?create=1'))}
            >
              <UserPlus />
              Create user
            </CommandItem>
          )}
          {hasPermission('order-write') && (
            <CommandItem
              value="manage strategies configuration order storage"
              onSelect={() => run(() => navigate('/strategies'))}
            >
              <SlidersHorizontal />
              Manage strategies
            </CommandItem>
          )}
          {/*
            Task 10 (defect-burndown): gated on `report-write` as the
            manager-level permission proxy -- verified against
            infrastructure/keycloak/karyo-realm.json that ADMIN and MANAGER
            both carry `report-write` and OPERATOR/VIEWER don't (no fallback
            needed). Previously ungated: VIEWER saw these commands and got a
            403 toast on select.

            Task 11 (defect-burndown): ALSO gated on `useDemoEnabled()` --
            these commands are dead surface (their target, SampleDataCard,
            doesn't mount) on any deployment with `KARYO_DEMO` off, which is
            the default. Composes with the permission gate; either condition
            failing hides the commands.

            Both commands NAVIGATE to `/` (the Operations Control page,
            where `SampleDataCard` lives) instead of calling
            loadSampleData()/resetSampleData() directly. Task 9 made seed
            destructive (auto-resets first) and added an AlertDialog confirm
            on the card's button -- firing the API straight from here would
            bypass that confirm, the same "destructive without confirm"
            defect class Task 9 just closed. Routing to the card's own
            confirmed flow is cheaper than lifting confirm state up here, and
            command-palette.spec.ts never asserts these two commands execute
            an API call directly (verified) so this doesn't break a test
            contract. Copy uses the "…" needs-further-interaction convention.
          */}
          {hasPermission('report-write') && demoEnabled && (
            <CommandItem
              value="load sample data"
              onSelect={() => run(() => navigate('/'))}
            >
              <Database />
              Load sample data…
            </CommandItem>
          )}
          {hasPermission('report-write') && demoEnabled && (
            <CommandItem
              value="reset sample data"
              onSelect={() => run(() => navigate('/'))}
            >
              <RotateCcw />
              Reset sample data…
            </CommandItem>
          )}
          <CommandItem value="toggle dark mode theme" onSelect={() => run(toggleDarkMode)}>
            {theme === 'dark' ? <Sun /> : <Moon />}
            Toggle dark mode
          </CommandItem>
        </CommandGroup>

        {productMatches.length > 0 && (
          <>
            <CommandSeparator />
            <CommandGroup heading="Products">
              {productMatches.map((product) => (
                <CommandItem
                  key={product.id}
                  value={`${product.name} ${product.number}`}
                  onSelect={() =>
                    run(() =>
                      navigate(
                        `/products?q=${encodeURIComponent(product.number)}`,
                      ),
                    )
                  }
                >
                  <Package />
                  <span>{product.name}</span>
                  <span className="ml-auto font-mono text-xs text-muted-foreground">
                    {product.number}
                  </span>
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        )}
      </CommandList>

      {/* v1.6 expectation-setter */}
      <div className="flex items-center gap-2 border-t px-3 py-2 text-xs text-muted-foreground">
        <Sparkles className="size-3.5" />
        AI commands coming soon
      </div>
    </CommandDialog>
  );
}
