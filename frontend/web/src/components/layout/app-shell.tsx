import { Outlet } from 'react-router';
import { SidebarProvider, SidebarInset } from '@/components/ui/sidebar';
import { AppSidebar } from '@/components/layout/app-sidebar';
import { AppHeader } from '@/components/layout/app-header';
import { SampleDataProvider } from '@/features/sample-data/sample-data-provider';
import { CommandPaletteProvider } from '@/components/command/command-palette-provider';
import { CommandPalette } from '@/components/command/command-palette';
import { ControlPrefsProvider } from '@/pages/home/ops/control-prefs-provider';
import { CopilotPanel } from '@/features/copilot/copilot-panel';

/**
 * Main application shell layout.
 * Wraps sidebar + header + content area.
 * Used as the layout route component for all authenticated pages.
 *
 * SampleDataProvider wraps the header too so the ⌘K palette (opened from
 * the header search pill) can trigger sample-data actions.
 */
export function AppShell() {
  return (
    <ControlPrefsProvider>
      <SidebarProvider>
        <AppSidebar />
        <SidebarInset>
          <SampleDataProvider>
            <CommandPaletteProvider>
              <AppHeader />
              <main className="flex-1 overflow-auto p-[var(--bpad,20px_24px_30px)]">
                <Outlet />
              </main>
              <CommandPalette />
              <CopilotPanel />
            </CommandPaletteProvider>
          </SampleDataProvider>
        </SidebarInset>
      </SidebarProvider>
    </ControlPrefsProvider>
  );
}
