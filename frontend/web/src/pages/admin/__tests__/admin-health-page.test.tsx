import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { createElement } from 'react';

const { AdminHealthPage } = await import('../admin-health-page');

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    createElement(QueryClientProvider, { client: qc }, createElement(AdminHealthPage)),
  );
}

const HEALTH_PAYLOAD = {
  status: 'UP',
  checks: [
    { name: 'Database connections health check', status: 'UP' },
    { name: 'Keycloak connection health check', status: 'DOWN', data: { reason: 'timeout' } },
  ],
};

describe('AdminHealthPage', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('fetches /q/health via plain fetch (not the JWT-attaching api client) and renders overall + per-check status', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => HEALTH_PAYLOAD,
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    renderPage();

    await waitFor(() => expect(screen.getByText(/Overall status: UP/)).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledWith('/q/health');
    expect(screen.getByText('Database connections health check')).toBeInTheDocument();
    expect(screen.getByText('Keycloak connection health check')).toBeInTheDocument();
  });

  it('falls back to /q/health/ready when /q/health is unreachable', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/q/health') return Promise.resolve({ ok: false, status: 404, json: async () => ({}) });
      return Promise.resolve({ ok: true, json: async () => ({ status: 'UP', checks: [] }) });
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    renderPage();

    await waitFor(() => expect(screen.getByText(/Overall status: UP/)).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledWith('/q/health');
    expect(fetchMock).toHaveBeenCalledWith('/q/health/ready');
  });

  it('shows an honest error state (not a fabricated UP) when both endpoints fail', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 502, json: async () => ({}) });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-health-error')).toBeInTheDocument());
    expect(screen.queryByText(/Overall status/)).not.toBeInTheDocument();
  });
});
