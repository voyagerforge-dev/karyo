import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { SPI_CATALOG } from '@/pages/admin/spi-catalog';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { AdminStrategiesPage } = await import('../admin-strategies-page');

const LIVE_EXTENSIONS = [
  {
    spiInterface: 'ProductLookup',
    spiFqn: 'com.karyo.product.spi.ProductLookup',
    module: 'karyo-product',
    implementations: ['DefaultProductLookup'],
    implementationCount: 1,
  },
  {
    spiInterface: 'Detector',
    spiFqn: 'com.karyo.monitors.spi.Detector',
    module: 'karyo-monitors',
    implementations: ['ExpiryRiskDetector', 'StuckOrderDetector'],
    implementationCount: 2,
  },
];

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AdminStrategiesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminStrategiesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('is titled "Extensions (SPI)" (renamed from Strategies to kill the collision)', async () => {
    mockApi.get.mockResolvedValue(LIVE_EXTENSIONS);
    renderPage();
    expect(screen.getByRole('heading', { name: 'Extensions (SPI)' })).toBeInTheDocument();
  });

  describe('live registry (B14)', () => {
    beforeEach(() => {
      mockApi.get.mockResolvedValue(LIVE_EXTENSIONS);
    });

    it('renders live rows grouped by module, with a live indicator and no fallback note', async () => {
      renderPage();

      expect(await screen.findByTestId('admin-extensions-live')).toBeInTheDocument();
      expect(screen.getByTestId('admin-strategies-live-indicator')).toBeInTheDocument();
      expect(screen.getByText('ProductLookup')).toBeInTheDocument();
      expect(screen.getByText('Detector')).toBeInTheDocument();
      expect(screen.getByText('DefaultProductLookup')).toBeInTheDocument();
      expect(screen.getByText('ExpiryRiskDetector')).toBeInTheDocument();
      expect(screen.getByText('karyo-product')).toBeInTheDocument();
      expect(screen.getByText('karyo-monitors')).toBeInTheDocument();
      expect(screen.queryByTestId('admin-strategies-fallback-note')).not.toBeInTheDocument();
    });

    it('mentions the reference HazmatStockFilter extension honestly (B15)', async () => {
      renderPage();
      await screen.findByTestId('admin-extensions-live');
      expect(screen.getByText(/HazmatStockFilter/)).toBeInTheDocument();
      expect(screen.getByText(/not loaded in the running app by default/i)).toBeInTheDocument();
    });
  });

  describe('fallback to the static catalog (honest degradation)', () => {
    it('falls back to the static catalog with a note when the live endpoint errors', async () => {
      mockApi.get.mockRejectedValue(new Error('403 Forbidden'));
      renderPage();

      await waitFor(() =>
        expect(screen.getByTestId('admin-strategies-fallback-note')).toBeInTheDocument(),
      );
      expect(screen.queryByTestId('admin-extensions-live')).not.toBeInTheDocument();
      expect(screen.getByText('Detector')).toBeInTheDocument();
      expect(screen.getByText('Forecast Model')).toBeInTheDocument();
      expect(screen.getByText('Slotting Strategy')).toBeInTheDocument();
      expect(screen.getByText('Reorder Policy Simulator')).toBeInTheDocument();
    });

    it('falls back to the static catalog with a note when the live endpoint returns empty', async () => {
      mockApi.get.mockResolvedValue([]);
      renderPage();

      await waitFor(() =>
        expect(screen.getByTestId('admin-strategies-fallback-note')).toBeInTheDocument(),
      );
      expect(screen.getByText('abc-proximity')).toBeInTheDocument();
      expect(screen.getByText('lost-sales')).toBeInTheDocument();
    });

    it('links the ADR-036 strategies entry to the live /strategies screen', async () => {
      mockApi.get.mockRejectedValue(new Error('403 Forbidden'));
      renderPage();
      await waitFor(() => screen.getByTestId('admin-strategies-fallback-note'));
      const link = screen.getByRole('link', { name: /strategies/i });
      expect(link).toHaveAttribute('href', '/strategies');
    });

    it('has no fabricated Copilot governance controls', async () => {
      mockApi.get.mockRejectedValue(new Error('403 Forbidden'));
      renderPage();
      await waitFor(() => screen.getByTestId('admin-strategies-fallback-note'));
      expect(screen.queryByText(/copilot: auto/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/copilot governance/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/save\s*&\s*apply/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/test in staging/i)).not.toBeInTheDocument();
    });

    it('shows an honest SPI-runtime strip (real counts, no fabricated governed/health tiles)', async () => {
      mockApi.get.mockRejectedValue(new Error('403 Forbidden'));
      renderPage();
      await waitFor(() => screen.getByTestId('admin-strategies-fallback-note'));
      // Real derived tiles.
      const seamsLabel = screen.getByText('Extension seams');
      expect(seamsLabel).toBeInTheDocument();
      expect(screen.getByText('Modules')).toBeInTheDocument();
      // The seam count (in the "Extension seams" cell) equals the real catalog length.
      const seamsCell = seamsLabel.parentElement as HTMLElement;
      expect(within(seamsCell).getByText(String(SPI_CATALOG.length))).toBeInTheDocument();
      // No fabricated tiles.
      expect(screen.queryByText(/copilot-governed/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/runtime health/i)).not.toBeInTheDocument();
    });
  });
});
