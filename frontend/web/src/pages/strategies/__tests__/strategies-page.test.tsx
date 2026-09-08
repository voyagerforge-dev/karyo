import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OrderStrategyResponse, StorageStrategyResponse } from '@/types/strategies';

// Radix Select (order-strategy-form) needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const createOrderMutate = vi.fn();
const updateOrderMutate = vi.fn();
const createStorageMutate = vi.fn();
const updateStorageMutate = vi.fn();

const orderStrategy: OrderStrategyResponse = {
  id: 1,
  name: 'DEFAULT',
  useLockedStock: false,
  preferComplete: true,
  preferMatching: false,
  completeHandling: 0,
  enforceLot: false,
  shortPickMode: 'SUBSTITUTE_ONLY',
  shortfallStrategy: 'PARTIAL_SHIP',
  pickDifferenceStrategy: 'LEAVE',
  packoutStrategy: 'ONE_TO_ONE',
  extensionProperties: {},
  sendToPacking: false,
  sendToShipping: false,
  createShippingOrder: false,
  createTypeOrders: false,
  defaultDestinationLocationId: null,
  defaultDestinationLocationName: null,
};

const storageStrategy: StorageStrategyResponse = {
  id: 2,
  name: 'NEAR-PICK',
  zoneId: 3,
  mixItem: true,
  mixClient: false,
  nearPickingLocation: true,
  sorts: 'allocation,position',
  onlyClientLocation: false,
  manualSearch: false,
  useAreaStrategyDate: false,
  useItemDataArea: false,
  created: '2026-07-01T00:00:00Z',
  modified: '2026-07-01T00:00:00Z',
};

vi.mock('../use-strategies', () => ({
  useOrderStrategies: vi.fn(() => ({ data: [orderStrategy], isLoading: false })),
  useStorageStrategies: vi.fn(() => ({ data: [storageStrategy], isLoading: false })),
  useCreateOrderStrategy: vi.fn(() => ({ mutate: createOrderMutate, isPending: false })),
  useUpdateOrderStrategy: vi.fn(() => ({ mutate: updateOrderMutate, isPending: false })),
  useCreateStorageStrategy: vi.fn(() => ({ mutate: createStorageMutate, isPending: false })),
  useUpdateStorageStrategy: vi.fn(() => ({ mutate: updateStorageMutate, isPending: false })),
}));

let mockRoles = ['order-read', 'order-write', 'layout-read', 'layout-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

// Import after mocks
import { StrategiesPage } from '../strategies-page';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <StrategiesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  createOrderMutate.mockClear();
  updateOrderMutate.mockClear();
  createStorageMutate.mockClear();
  updateStorageMutate.mockClear();
});

afterEach(() => {
  mockRoles = ['order-read', 'order-write', 'layout-read', 'layout-write'];
});

describe('StrategiesPage', () => {
  it('lists order strategies by default', () => {
    renderPage();
    expect(screen.getByText('DEFAULT')).toBeInTheDocument();
  });

  it('kind chips switch between order and storage strategies with per-kind permissions', async () => {
    const user = userEvent.setup();

    // Full permissions -- both chips render, order active by default.
    const first = renderPage();
    expect(screen.getByRole('button', { name: /order strategies/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /storage strategies/i })).toBeInTheDocument();
    expect(screen.getByText('DEFAULT')).toBeInTheDocument();
    expect(screen.queryByText('NEAR-PICK')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /storage strategies/i }));
    expect(screen.getByText('NEAR-PICK')).toBeInTheDocument();
    expect(screen.queryByText('DEFAULT')).not.toBeInTheDocument();
    first.unmount();

    // order-read only -- Storage chip hidden, list shows order strategies.
    mockRoles = ['order-read', 'order-write'];
    const second = renderPage();
    expect(screen.getByRole('button', { name: /order strategies/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /storage strategies/i })).not.toBeInTheDocument();
    expect(screen.getByText('DEFAULT')).toBeInTheDocument();
    second.unmount();

    // layout-read only -- Order chip hidden, default view falls back to storage.
    mockRoles = ['layout-read', 'layout-write'];
    renderPage();
    expect(screen.queryByRole('button', { name: /order strategies/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /storage strategies/i })).toBeInTheDocument();
    expect(screen.getByText('NEAR-PICK')).toBeInTheDocument();
  });

  it('selecting a strategy shows the read workspace; Edit opens the form prefilled', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('DEFAULT'));
    expect(screen.getByText(/substitute only/i)).toBeInTheDocument();

    await user.click(screen.getByTestId('strategy-edit'));
    const nameInput = screen.getByLabelText(/^name$/i);
    expect(nameInput).toHaveValue('DEFAULT');
    expect(nameInput).toBeDisabled();
    // Row 8's "Default destination" field also renders role="combobox" (LocationPicker's search
    // input, unset here) -- scope by label to disambiguate from the shortPickMode Select.
    expect(screen.getByLabelText(/short pick mode/i)).toHaveTextContent(/substitute only/i);
  });

  it('create opens the empty form for the active kind', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('strategy-create'));
    expect(screen.getByRole('heading', { name: /new order strategy/i })).toBeInTheDocument();
    const nameInput = screen.getByLabelText(/^name$/i);
    expect(nameInput).toHaveValue('');
    expect(nameInput).not.toBeDisabled();

    await user.type(nameInput, 'NEW-STRAT');
    await user.click(screen.getByTestId('order-strategy-submit'));
    expect(createOrderMutate).toHaveBeenCalledTimes(1);
  });

  it('the JSONB editor rejects invalid JSON', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByTestId('strategy-create'));
    const ext = screen.getByLabelText(/extension properties/i);
    await user.clear(ext);
    await user.type(ext, '{{not json');
    await user.click(screen.getByTestId('order-strategy-submit'));
    expect(screen.getByText(/must be a JSON object/i)).toBeInTheDocument();
    expect(createOrderMutate).not.toHaveBeenCalled();
  });

  it('storage strategy create opens the storage form', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /storage strategies/i }));
    await user.click(screen.getByTestId('strategy-create'));
    expect(screen.getByRole('heading', { name: /new storage strategy/i })).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^name$/i), 'ZONE-A');
    await user.click(screen.getByTestId('storage-strategy-submit'));
    expect(createStorageMutate).toHaveBeenCalledTimes(1);
  });

  it('New strategy button is hidden without write permission for the active kind', () => {
    mockRoles = ['order-read', 'layout-read'];
    renderPage();
    expect(screen.queryByTestId('strategy-create')).not.toBeInTheDocument();
  });

  it('Edit button is hidden without write permission for the active kind', async () => {
    const user = userEvent.setup();
    mockRoles = ['order-read', 'layout-read', 'layout-write'];
    renderPage();

    await user.click(screen.getByText('DEFAULT'));
    expect(screen.queryByTestId('strategy-edit')).not.toBeInTheDocument();
  });

  it('strategy-create is visible only for the kind with write permission (layout-write)', async () => {
    const user = userEvent.setup();
    mockRoles = ['order-read', 'layout-read', 'layout-write'];
    renderPage();

    // Order chip active by default, no order-write permission -- create button hidden
    expect(screen.queryByTestId('strategy-create')).not.toBeInTheDocument();

    // Switch to Storage chip, has layout-write permission -- create button visible
    await user.click(screen.getByRole('button', { name: /storage strategies/i }));
    expect(screen.getByTestId('strategy-create')).toBeInTheDocument();
  });

  it('strategy-create is visible only for the kind with write permission (order-write)', async () => {
    const user = userEvent.setup();
    mockRoles = ['order-read', 'layout-read', 'order-write'];
    renderPage();

    // Order chip active by default, has order-write permission -- create button visible
    expect(screen.getByTestId('strategy-create')).toBeInTheDocument();

    // Switch to Storage chip, no layout-write permission -- create button hidden
    await user.click(screen.getByRole('button', { name: /storage strategies/i }));
    expect(screen.queryByTestId('strategy-create')).not.toBeInTheDocument();
  });
});
