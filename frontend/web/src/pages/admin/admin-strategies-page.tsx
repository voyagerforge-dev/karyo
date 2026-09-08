import { useState } from 'react';
import { Puzzle, ArrowUpRight, Lock, Unlock, Radio } from 'lucide-react';
import { Link } from 'react-router';
import { cn } from '@/lib/utils';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
} from '@/components/master-detail/master-detail';
import { useAdminPrefs } from '@/pages/admin/use-admin-prefs';
import { useExtensions, type ExtensionInfo } from '@/pages/admin/use-extensions';
import { SPI_CATALOG, type SpiSeam } from '@/pages/admin/spi-catalog';

/**
 * Admin -> Extensions (SPI): the extension registry. Phase B14 rewired this
 * from a curated static catalog to the LIVE `GET /api/v1/admin/extensions`
 * endpoint (resolved at request time via CDI `BeanManager` — reflects what
 * is actually loaded in the running app). The static `SPI_CATALOG` is kept
 * as an honest fallback: if the live endpoint errors (e.g. non-ADMIN caller)
 * or returns nothing, the page renders the bundled catalog instead of a
 * blank/broken screen, with a note that the data shown is not live.
 */
export function AdminStrategiesPage() {
  const { data: liveExtensions, isLoading, isError } = useExtensions();
  const hasLiveData = !isLoading && !isError && (liveExtensions?.length ?? 0) > 0;
  const showFallbackNote = !isLoading && (isError || (liveExtensions?.length ?? 0) === 0);

  return (
    <div data-testid="admin-strategies-page">
      {/* Heading */}
      <div className="mb-5 flex items-start justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
            Extensions (SPI)
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            The extension registry — every SPI interface Karyo ships, and what implements it today.
            Read-only: compile a new implementation against the module&apos;s <code>-api</code> jar
            and drop it on the classpath to extend one.
          </p>
        </div>
        {hasLiveData && (
          <span
            className="flex flex-none items-center gap-1.5 rounded-full px-2.5 py-1 text-[10.5px] font-bold"
            style={{ color: '#7FB77E', background: 'rgba(127,183,126,0.14)' }}
            data-testid="admin-strategies-live-indicator"
          >
            <Radio className="size-3" /> Live
          </span>
        )}
      </div>

      {hasLiveData ? (
        <LiveExtensionsView extensions={liveExtensions ?? []} />
      ) : (
        <StaticCatalogView showFallbackNote={showFallbackNote} />
      )}

      {/* B15 — honest note about the reference extension example. */}
      <p className="mt-5 text-[12px] leading-[1.55] text-muted-foreground/70">
        A reference example extension ships in <code className="numeric">karyo-inventory-ext-example</code>{' '}
        (<code className="numeric">HazmatStockFilter</code>, a drop-in <code className="numeric">StockSelectionFilter</code>
        ) demonstrating the drop-in-JAR extensibility pattern. It is not loaded in the running
        app by default — it ships as source you can build and drop on the classpath.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Live view (B14)
// ---------------------------------------------------------------------------

function LiveExtensionsView({ extensions }: { extensions: ExtensionInfo[] }) {
  const moduleCount = new Set(extensions.map((e) => e.module)).size;
  const totalImplementations = extensions.reduce((sum, e) => sum + e.implementationCount, 0);
  const tiles = [
    { label: 'Extension seams', value: String(extensions.length), accent: false },
    { label: 'Loaded implementations', value: String(totalImplementations), accent: true },
    { label: 'Modules', value: String(moduleCount), accent: false },
  ];

  const byModule = new Map<string, ExtensionInfo[]>();
  for (const e of extensions) {
    const list = byModule.get(e.module) ?? [];
    list.push(e);
    byModule.set(e.module, list);
  }
  const modules = [...byModule.keys()].sort();

  return (
    <div data-testid="admin-extensions-live">
      {/* Stat strip */}
      <div className="mb-5 flex flex-col overflow-hidden rounded-2xl border border-border bg-card sm:flex-row">
        {tiles.map((t, i) => (
          <div
            key={t.label}
            className={cn(
              'flex-1 px-[18px] py-[15px]',
              i < tiles.length - 1 && 'border-b border-border sm:border-b-0 sm:border-r',
            )}
          >
            <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
              {t.label}
            </div>
            <div
              className="numeric mt-2 text-[23px] font-bold"
              style={{ color: t.accent ? '#B7ACEC' : undefined }}
            >
              {t.value}
            </div>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-5">
        {modules.map((module) => (
          <section key={module} className="rounded-2xl border border-border bg-card p-5">
            <h2 className="numeric mb-3 text-[11px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
              {module}
            </h2>
            <div className="flex flex-col gap-2">
              {byModule.get(module)!.map((e) => (
                <div
                  key={e.spiFqn}
                  className="rounded-xl border border-border bg-background px-[13px] py-[11px]"
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2.5">
                      <div
                        className="flex size-[30px] flex-none items-center justify-center rounded-[9px] border"
                        style={{
                          background: e.implementationCount > 0 ? 'rgba(124,108,207,0.12)' : 'var(--background)',
                          borderColor: e.implementationCount > 0 ? 'rgba(124,108,207,0.3)' : 'var(--border)',
                          color: e.implementationCount > 0 ? '#B7ACEC' : '#A39B86',
                        }}
                      >
                        <Puzzle className="size-[15px]" />
                      </div>
                      <div>
                        <div className="text-[13.5px] font-semibold text-foreground">
                          {e.spiInterface}
                        </div>
                        <div className="numeric mt-0.5 text-[11px] text-muted-foreground/70">{e.spiFqn}</div>
                      </div>
                    </div>
                    <span className="numeric flex-none rounded-full bg-secondary px-2 py-0.5 text-[10.5px] font-semibold text-muted-foreground">
                      {e.implementationCount}
                    </span>
                  </div>
                  <div className="mt-2.5 flex flex-wrap gap-1.5">
                    {e.implementations.length > 0 ? (
                      e.implementations.map((impl) => (
                        <span
                          key={impl}
                          className="numeric rounded-md border border-border bg-card px-2 py-1 text-[11px] text-foreground/75"
                        >
                          {impl}
                        </span>
                      ))
                    ) : (
                      <span className="text-[11px] italic text-muted-foreground/70">
                        No implementation currently loaded
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Static fallback view (pre-B14 catalog)
// ---------------------------------------------------------------------------

function StaticCatalogView({ showFallbackNote }: { showFallbackNote: boolean }) {
  const prefs = useAdminPrefs();
  const showSpiIds = prefs.showSpiIds;

  const [selectedId, setSelectedId] = useState<string>(
    SPI_CATALOG.find((s) => s.configuredAt)?.id ?? SPI_CATALOG[0].id,
  );
  const [search, setSearch] = useState('');

  const selected = SPI_CATALOG.find((s) => s.id === selectedId) ?? SPI_CATALOG[0];
  const extensibleCount = SPI_CATALOG.filter((s) => s.extensible).length;

  const filtered = SPI_CATALOG.filter((s) =>
    s.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  const moduleCount = new Set(SPI_CATALOG.map((s) => s.module)).size;
  const tiles = [
    { label: 'Extension seams', value: String(SPI_CATALOG.length), accent: false },
    { label: 'Open for a drop-in JAR', value: String(extensibleCount), accent: true },
    { label: 'Modules', value: String(moduleCount), accent: false },
  ];

  return (
    <div>
      {showFallbackNote && (
        <p
          data-testid="admin-strategies-fallback-note"
          className="mb-4 rounded-xl border px-3.5 py-2.5 text-[12px]"
          style={{ borderColor: 'rgba(224,164,90,0.3)', background: 'rgba(224,164,90,0.08)', color: '#E0A45A' }}
        >
          Showing bundled catalog (live registry unavailable).
        </p>
      )}

      {/* Stat strip */}
      <div className="mb-5 flex flex-col overflow-hidden rounded-2xl border border-border bg-card sm:flex-row">
        {tiles.map((t, i) => (
          <div
            key={t.label}
            className={cn(
              'flex-1 px-[18px] py-[15px]',
              i < tiles.length - 1 && 'border-b border-border sm:border-b-0 sm:border-r',
            )}
          >
            <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
              {t.label}
            </div>
            <div
              className="numeric mt-2 text-[23px] font-bold"
              style={{ color: t.accent ? '#B7ACEC' : undefined }}
            >
              {t.value}
            </div>
          </div>
        ))}
      </div>

      {/* Master-detail */}
      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search extension seams…"
          >
            {filtered.map((seam) => (
              <MasterListRow
                key={seam.id}
                tone={seam.extensible ? 'violet' : 'grey'}
                active={seam.id === selectedId}
                onClick={() => setSelectedId(seam.id)}
              >
                <div className="flex items-center gap-3">
                  <div
                    className="flex size-[34px] flex-none items-center justify-center rounded-[10px] border"
                    style={{
                      background: seam.extensible ? 'rgba(124,108,207,0.12)' : 'var(--background)',
                      borderColor: seam.extensible ? 'rgba(124,108,207,0.3)' : 'var(--border)',
                      color: seam.extensible ? '#B7ACEC' : '#A39B86',
                    }}
                  >
                    <Puzzle className="size-[17px]" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-[14px] font-semibold text-foreground">{seam.name}</div>
                    {showSpiIds && (
                      <div className="numeric mt-0.5 truncate text-[11px] text-muted-foreground/70">
                        {seam.spi}
                      </div>
                    )}
                  </div>
                  {seam.extensible ? (
                    <Unlock className="size-3.5 flex-none text-chart-5" aria-label="Extensible" />
                  ) : (
                    <Lock className="size-3.5 flex-none text-muted-foreground/70" aria-label="Fixed, single implementation" />
                  )}
                </div>
                <div className="mt-2.5 flex flex-wrap gap-1.5">
                  {seam.builtIns.map((b) => (
                    <span
                      key={b}
                      className="numeric rounded-md border border-border bg-background px-2 py-1 text-[11px] text-foreground/75"
                    >
                      {b}
                    </span>
                  ))}
                </div>
              </MasterListRow>
            ))}
          </MasterList>
        }
        detail={<DetailPanel key={selected.id} seam={selected} showSpiIds={showSpiIds} />}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Detail panel
// ---------------------------------------------------------------------------

function DetailPanel({ seam, showSpiIds }: { seam: SpiSeam; showSpiIds: boolean }) {
  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <div className="mb-0.5 flex items-center justify-between">
        <h2 className="text-base font-semibold text-foreground">{seam.name}</h2>
        <span
          className="rounded-full px-2 py-0.5 text-[10.5px] font-bold"
          style={
            seam.extensible
              ? { color: '#B7ACEC', background: 'rgba(124,108,207,0.16)' }
              : { color: '#8C836F', background: '#231F18' }
          }
        >
          {seam.extensible ? 'Extensible' : 'Fixed'}
        </span>
      </div>

      {showSpiIds && (
        <p className="numeric mb-4 mt-1 break-all text-[11px] text-muted-foreground/70">{seam.spi}</p>
      )}

      <SectionLabel>Module</SectionLabel>
      <p className="numeric mb-[18px] text-[13px] text-foreground/85">{seam.module}</p>

      <SectionLabel>Shipped implementation{seam.builtIns.length > 1 ? 's' : ''}</SectionLabel>
      <ul className="mb-[18px] flex flex-col gap-1.5">
        {seam.builtIns.map((b) => (
          <li
            key={b}
            className="numeric rounded-xl border border-border bg-background px-[13px] py-[11px] text-[13px] text-foreground/85"
          >
            {b}
          </li>
        ))}
      </ul>

      <SectionLabel>Notes</SectionLabel>
      <p className="mb-[18px] text-[12.5px] leading-[1.55] text-muted-foreground">{seam.note}</p>

      {seam.extensible && (
        <p className="mb-[18px] text-[12px] leading-[1.5] text-muted-foreground/70">
          To extend: implement <code className="numeric text-muted-foreground">{seam.spi}</code> in a new
          module that compiles against the sibling <code className="numeric text-muted-foreground">-api</code>{' '}
          jar, then drop the built JAR on the classpath — zero changes to core.
        </p>
      )}

      {seam.configuredAt && (
        <Link
          to={seam.configuredAt}
          className="font-display flex h-11 items-center justify-center gap-1.5 rounded-xl bg-primary text-[14px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
        >
          Manage at {seam.configuredAt}
          <ArrowUpRight className="size-4" />
        </Link>
      )}
    </section>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="numeric mb-2.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
      {children}
    </div>
  );
}
