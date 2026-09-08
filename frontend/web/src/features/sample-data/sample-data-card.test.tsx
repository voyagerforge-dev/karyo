import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { SampleDataProvider } = await import(
  '@/features/sample-data/sample-data-provider'
);
const { SampleDataCard } = await import('@/features/sample-data/sample-data-card');

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    createElement(
      QueryClientProvider,
      { client: queryClient },
      createElement(SampleDataProvider, null, createElement(SampleDataCard)),
    ),
  );
}

describe('SampleDataCard -- Load confirm dialog', () => {
  beforeEach(() => vi.clearAllMocks());

  it('clicking Load Sample Data opens a confirm dialog without calling the API yet', async () => {
    renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Load Sample Data' }));

    expect(
      screen.getByText('Load sample data?'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/resets the demo warehouse first/i),
    ).toBeInTheDocument();
    expect(mockApi.post).not.toHaveBeenCalled();
  });

  it('confirming the dialog calls POST /api/v1/demo/seed and reaches the loaded state', async () => {
    mockApi.post.mockResolvedValue({
      locations: 40,
      skus: 24,
      orders: 960,
      picks: 2400,
      shipments: 900,
      counts: 30,
      goodsReceipts: 120,
      transportOrders: 300,
      alertsTripped: [],
    });
    renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Load Sample Data' }));
    const dialog = screen.getByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Load Sample Data' }));

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/demo/seed', {});
    await waitFor(() =>
      expect(screen.getByText('Sample Data Loaded')).toBeInTheDocument(),
    );
  });

  it('cancelling the dialog never calls the API', async () => {
    renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Load Sample Data' }));
    const dialog = screen.getByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await waitFor(() =>
      expect(screen.queryByText('Load sample data?')).not.toBeInTheDocument(),
    );
    expect(mockApi.post).not.toHaveBeenCalled();
  });
});
