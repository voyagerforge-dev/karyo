import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { PaginatedResponse } from '@/types/api';
import type { DeliveryOrderResponse } from '@/types/orders';

// Mock the API client so ProductPicker's query never hits the network. The
// order-detail Activity feed (useOrderActivity) also hits /api/v1/journals —
// route it to an empty array so it renders the honest empty state.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn((url: string) => {
      if (typeof url === 'string' && url.startsWith('/api/v1/journals')) {
        return Promise.resolve([]);
      }
      return Promise.resolve({ content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } });
    }),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

// Mock the hooks module (shared by page, form, and detail)
const createMutate = vi.fn();
const updateMutate = vi.fn();
vi.mock('../use-orders', () => ({
  useDeliveryOrders: vi.fn(),
  useDeliveryOrder: vi.fn(() => ({ data: undefined, isLoading: false })),
  useCreateDeliveryOrder: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateDeliveryOrder: vi.fn(() => ({ mutate: updateMutate, isPending: false })),
  useReleaseOrder: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useRetryReservation: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useCancelOrder: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useClaimOrder: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useReleaseOperator: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useOrderStrategies: vi.fn(() => ({
    data: [{
      id: 1,
      name: 'DEFAULT',
      useLockedStock: false,
      preferComplete: true,
      preferMatching: false,
      completeHandling: 0,
      enforceLot: false,
      shortPickMode: 'FOLLOW_UP_THEN_SUBSTITUTE',
      shortfallStrategy: 'PARTIAL_SHIP',
      pickDifferenceStrategy: 'LEAVE',
      packoutStrategy: 'ONE_TO_ONE',
      extensionProperties: {},
    }],
    isLoading: false,
  })),
  sortingStateToString: vi.fn(() => undefined),
}));

// Detail pulls in the picking release hook
vi.mock('@/features/picking/use-pick-orders', () => ({
  useReleaseToPicking: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

// D12: CSV export button wiring -- mocked so the click assertion doesn't hit the network.
const saveCsvMock = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  saveCsv: (...args: unknown[]) => saveCsvMock(...args),
  viewPdf: vi.fn(),
  saveZpl: vi.fn(),
  archiveDocument: vi.fn(),
}));

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: ['order-read', 'order-write'],
    hasPermission: (p: string) => p === 'order-read' || p === 'order-write',
    hasAnyPermission: () => true,
  })),
}));

// Row 10: order-detail's claim/release affordance needs an identity.
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({ userName: 'op-1' })),
}));

const mockOrders: DeliveryOrderResponse[] = [
  {
    id: 1,
    orderNumber: 'DO-1001',
    externalNumber: null,
    customerName: 'Acme Corp',
    street: 'Commerce Way',
    streetNumber: '92',
    zipCode: '94501',
    city: 'East Bay',
    country: 'US',
    phone: '+1 555 0100',
    email: 'ops@acme.test',
    deliveryDate: '2026-06-20',
    prio: 50,
    notes: null,
    pickingHint: null,
    packingHint: null,
    shippingHint: null,
    state: 50,
    stateName: 'CREATED',
    orderStrategyId: null,
    clientId: 1,
    lines: [
      {
        id: 11,
        lineNumber: 1,
        itemDataId: 5,
        itemDataNumber: 'DEMO-MOUSE',
        itemDataName: 'Wireless Mouse',
        amount: 10,
        reservedAmount: 0,
        shortage: 10,
        state: 50,
        stateName: 'CREATED',
        lotNumber: null,
        unitPrice: 24.99,
        externalNumber: null,
        pickedAmount: 0,
        substitutedAmount: 0,
      },
      {
        id: 12,
        lineNumber: 2,
        itemDataId: 6,
        itemDataNumber: 'DEMO-KEYBOARD',
        itemDataName: null,
        amount: 5,
        reservedAmount: 0,
        shortage: 5,
        state: 50,
        stateName: 'CREATED',
        lotNumber: null,
        unitPrice: null,
        externalNumber: null,
        pickedAmount: 0,
        substitutedAmount: 0,
      },
    ],
    created: '2026-06-12T08:00:00Z',
    modified: '2026-06-12T08:00:00Z',
    carrierName: 'UPS',
    carrierService: 'GROUND',
    trackingNumber: '1Z999AA10123456784',
    shippedAt: '2026-06-13T10:00:00Z',
    destinationLocationId: null,
    destinationLocationName: null,
    operatorId: null,
    senderName: null,
    documentUrl: '/api/v1/delivery-orders/1/delivery-note.pdf',
    labelUrl: null,
  },
  {
    id: 2,
    orderNumber: 'DO-1002',
    externalNumber: 'EXT-7',
    customerName: 'Globex',
    street: null,
    streetNumber: null,
    zipCode: null,
    city: null,
    country: null,
    phone: null,
    email: null,
    deliveryDate: null,
    prio: 10,
    notes: null,
    pickingHint: null,
    packingHint: null,
    shippingHint: null,
    state: 300,
    stateName: 'PROCESSABLE',
    orderStrategyId: 1,
    clientId: 1,
    lines: [],
    created: '2026-06-12T09:00:00Z',
    modified: '2026-06-12T09:00:00Z',
    carrierName: null,
    carrierService: null,
    trackingNumber: null,
    shippedAt: null,
    destinationLocationId: null,
    destinationLocationName: null,
    operatorId: null,
    senderName: null,
    documentUrl: '/api/v1/delivery-orders/2/delivery-note.pdf',
    labelUrl: null,
  },
];

// DO-1003: a single line that is fully RESERVED but not yet PICKED
// (pickedAmount 0, substitutedAmount 0). Pins the real pick-progress fold —
// a reservation is not a pick, so this line must NOT count as picked.
const fullyReservedUnpickedOrder: DeliveryOrderResponse = {
  id: 3,
  orderNumber: 'DO-1003',
  externalNumber: null,
  customerName: 'Initech',
  street: null,
  streetNumber: null,
  zipCode: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  deliveryDate: null,
  prio: 50,
  notes: null,
  pickingHint: null,
  packingHint: null,
  shippingHint: null,
  state: 300,
  stateName: 'PROCESSABLE',
  orderStrategyId: null,
  clientId: 1,
  lines: [
    {
      id: 31,
      lineNumber: 1,
      itemDataId: 5,
      itemDataNumber: 'DEMO-MOUSE',
      itemDataName: 'Wireless Mouse',
      amount: 10,
      reservedAmount: 10,
      shortage: 0,
      state: 300,
      stateName: 'PROCESSABLE',
      lotNumber: null,
      unitPrice: null,
      externalNumber: null,
      pickedAmount: 0,
      substitutedAmount: 0,
    },
  ],
  created: '2026-06-12T10:00:00Z',
  modified: '2026-06-12T10:00:00Z',
  carrierName: null,
  carrierService: null,
  trackingNumber: null,
  shippedAt: null,
  destinationLocationId: null,
  destinationLocationName: null,
  operatorId: null,
  senderName: null,
  documentUrl: '/api/v1/delivery-orders/3/delivery-note.pdf',
  labelUrl: null,
};

const mockPaginatedResponse: PaginatedResponse<DeliveryOrderResponse> = {
  content: [...mockOrders, fullyReservedUnpickedOrder],
  page: { number: 0, size: 50, totalElements: 3, totalPages: 1 },
};

// Import after mocks
import { useDeliveryOrders, useDeliveryOrder } from '../use-orders';
import { OrdersPage } from '../orders-page';
import { OrderForm, patchField, patchIdField } from '../order-form';
import { api } from '@/lib/api-client';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <OrdersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  createMutate.mockClear();
  updateMutate.mockClear();
  saveCsvMock.mockClear();
  vi.mocked(useDeliveryOrders).mockReturnValue({
    data: mockPaginatedResponse,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useDeliveryOrders>);
  // Detail fetch falls back to the list row in the component, but stub it anyway.
  vi.mocked(useDeliveryOrder).mockReturnValue({
    data: undefined,
    isLoading: false,
  } as ReturnType<typeof useDeliveryOrder>);
});

describe('OrdersPage', () => {
  it('renders the master list with order numbers and customers', () => {
    renderPage();

    expect(screen.getByText('DO-1001')).toBeInTheDocument();
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('DO-1002')).toBeInTheDocument();
    expect(screen.getByText('Globex')).toBeInTheDocument();
  });

  it('renders the New order button for order-write users', () => {
    renderPage();

    expect(
      screen.getByRole('button', { name: /new order/i }),
    ).toBeInTheDocument();
  });

  it('renders the Export CSV button and wires it to the delivery-orders export endpoint', async () => {
    const user = userEvent.setup();
    renderPage();

    const exportBtn = screen.getByTestId('export-csv-btn');
    expect(exportBtn).toBeInTheDocument();

    await user.click(exportBtn);
    expect(saveCsvMock).toHaveBeenCalledWith('/api/v1/delivery-orders/export.csv', 'delivery-orders.csv');
  });

  it('shows the empty detail state until a row is selected', () => {
    renderPage();

    expect(
      screen.getByText(/select an order to view its fulfillment workspace/i),
    ).toBeInTheDocument();
  });

  it('loads the detail workspace when a list row is clicked', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('DO-1001'));

    // Detail header shows the order number + a line items table
    expect(screen.getByTestId('order-lines-table')).toBeInTheDocument();
    expect(screen.getByTestId('order-detail-status')).toBeInTheDocument();
  });

  it('opens the create dialog with header fields and a lines editor', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /new order/i }));

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { name: /create order/i }),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/customer/i)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^product$/i)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^amount$/i)).toBeInTheDocument();
  });

  it('create form validates that at least one line is filled', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /new order/i }));
    await user.click(await screen.findByTestId('order-form-submit'));

    expect(
      screen.getByText(/at least one line with a product/i),
    ).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it('master-list row counts a line as picked via pickedAmount+substitutedAmount, not reservedAmount — a fully-reserved-but-unpicked line does not count', () => {
    renderPage();

    // DO-1003's sole line is fully RESERVED (reservedAmount 10 === amount
    // 10) but has pickedAmount 0 / substitutedAmount 0 — a reservation is
    // not a pick. The old `reservedAmount >= amount` proxy would render
    // "1/1" here; the real fold must render "0/1".
    const row = screen.getByText('DO-1003').closest('button') as HTMLElement;
    expect(within(row).getByText('0/1')).toBeInTheDocument();
    expect(within(row).queryByText('1/1')).not.toBeInTheDocument();
  });
});

describe('OrderDetail ship-to', () => {
  it('renders the real ship-to address, phone, and email when present', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    expect(screen.getByText('Commerce Way 92')).toBeInTheDocument();
    expect(screen.getByText('East Bay 94501, US')).toBeInTheDocument();
    expect(screen.getByText('+1 555 0100')).toBeInTheDocument();
    expect(screen.getByText('ops@acme.test')).toBeInTheDocument();
  });

  it('shows the honest "Address not on file" gap for an address-less order', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1002'));

    expect(screen.getByText('Address not on file')).toBeInTheDocument();
  });

  it('shows the honest "—" for carrier, service, and tracking when no shipment exists', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1002'));

    const carrierRow = screen.getByText('Carrier').closest('div');
    const serviceRow = screen.getByText('Service').closest('div');
    const trackingRow = screen.getByText('Tracking').closest('div');
    expect(carrierRow).toHaveTextContent('—');
    expect(serviceRow).toHaveTextContent('—');
    expect(trackingRow).toHaveTextContent('—');
  });

  it('shows the real carrier, service, and tracking when a shipment exists', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    expect(screen.getByText('UPS')).toBeInTheDocument();
    expect(screen.getByText('GROUND')).toBeInTheDocument();
    expect(screen.getByText('1Z999AA10123456784')).toBeInTheDocument();
  });

  it('does not fabricate a channel in the detail subtitle', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    expect(screen.queryByText(/·\s*B2B\s*·/)).not.toBeInTheDocument();
    // subtitle still shows customer + created
    expect(screen.getByText(/Acme Corp · created/)).toBeInTheDocument();
  });

  it('shows the real 0% pick progress in the line FULFILL slot (not the old reservedAmount proxy)', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    // Fixture line 1 has pickedAmount 0 / substitutedAmount 0 — the derived
    // rollup is genuinely zero, so the readout is a real "0%", not a
    // fabricated dash and not a reservedAmount-derived percent.
    const row = screen.getByTestId('order-line-row-1');
    expect(within(row).getByText('0%')).toBeInTheDocument();
    expect(within(row).getByText('0 / 10')).toBeInTheDocument();
  });

  it('renders the product name as the line headline with the SKU as a secondary label', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    const row = screen.getByTestId('order-line-row-1');
    expect(within(row).getByText('Wireless Mouse')).toBeInTheDocument();
    expect(within(row).getByText(/DEMO-MOUSE/)).toBeInTheDocument();
  });

  it('falls back to the SKU as the headline when itemDataName is null (honest gap)', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByText('DO-1001'));

    const row = screen.getByTestId('order-line-row-2');
    expect(within(row).getByText('DEMO-KEYBOARD')).toBeInTheDocument();
  });
});

describe('OrderForm handling hints', () => {
  function renderForm(order?: DeliveryOrderResponse) {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <OrderForm order={order} onClose={vi.fn()} />
      </QueryClientProvider>,
    );
  }

  const defaultApiGet = vi.mocked(api.get).getMockImplementation();

  afterEach(() => {
    if (defaultApiGet) vi.mocked(api.get).mockImplementation(defaultApiGet);
  });

  it('submits picking/packing/shipping hints on create', async () => {
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (typeof url === 'string' && url.startsWith('/api/v1/products')) {
        return Promise.resolve({
          content: [{ id: 9, number: 'DEMO-MOUSE', name: 'Wireless Mouse' }],
          page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
        });
      }
      return Promise.resolve({
        content: [],
        page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
      });
    });

    const user = userEvent.setup();
    renderForm();

    const productInput = screen.getByLabelText('Product');
    await user.click(productInput);
    await user.type(productInput, 'mouse');
    await user.click(await screen.findByRole('option', { name: /DEMO-MOUSE/i }));
    await user.type(screen.getByLabelText('Amount'), '2');

    await user.type(screen.getByLabelText('Picking hint'), 'Fragile - top load');
    await user.type(screen.getByLabelText('Packing hint'), 'Double box');
    await user.type(screen.getByLabelText('Shipping hint'), 'Liftgate required');
    await user.click(screen.getByTestId('order-form-submit'));

    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        pickingHint: 'Fragile - top load',
        packingHint: 'Double box',
        shippingHint: 'Liftgate required',
      }),
      expect.anything(),
    );
  });

  it('prefills hint fields in edit mode; untouched non-empty stays a value, a previously-set field left blank clears (D2)', async () => {
    const order: DeliveryOrderResponse = {
      ...mockOrders[0],
      pickingHint: 'Existing pick note',
      packingHint: null,
      shippingHint: '   ',
    };
    const user = userEvent.setup();
    renderForm(order);

    expect(screen.getByLabelText('Picking hint')).toHaveValue('Existing pick note');
    expect(screen.getByLabelText('Packing hint')).toHaveValue('');
    expect(screen.getByLabelText('Shipping hint')).toHaveValue('   ');

    await user.clear(screen.getByLabelText('Packing hint'));
    await user.type(screen.getByLabelText('Packing hint'), 'Double box');
    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: order.id,
        // untouched, still non-empty after trim -> sent as a value (unchanged from before D2)
        pickingHint: 'Existing pick note',
        // typed into an empty field -> a value (unchanged from before D2)
        packingHint: 'Double box',
        // untouched, but the order HAD a (whitespace) value that trims to empty -> D2 clear,
        // not the pre-D2 `undefined` (this field was impossible to clear before this task)
        shippingHint: null,
      }),
      expect.anything(),
    );
  });

  it('omits a hint field that was already empty and stays untouched (D2 Absent)', async () => {
    const order: DeliveryOrderResponse = {
      ...mockOrders[0],
      pickingHint: null,
      packingHint: null,
      shippingHint: null,
    };
    const user = userEvent.setup();
    renderForm(order);

    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: order.id,
        notes: undefined,
        pickingHint: undefined,
        packingHint: undefined,
        shippingHint: undefined,
        externalNumber: undefined,
      }),
      expect.anything(),
    );
  });

  it('clears a previously-set notes field when the user blanks it out (D2 explicit null)', async () => {
    const order: DeliveryOrderResponse = { ...mockOrders[0], notes: 'Fragile' };
    const user = userEvent.setup();
    renderForm(order);

    await user.clear(screen.getByLabelText('Notes'));
    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: order.id, notes: null }),
      expect.anything(),
    );
  });
});

describe('OrderForm senderName + destinationLocationId tri-state (:1457)', () => {
  function renderForm(order?: DeliveryOrderResponse) {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <OrderForm order={order} onClose={vi.fn()} />
      </QueryClientProvider>,
    );
  }

  it('omits senderName and destinationLocationId when both were already unset and stay untouched', async () => {
    const order: DeliveryOrderResponse = {
      ...mockOrders[0],
      senderName: null,
      destinationLocationId: null,
      destinationLocationName: null,
    };
    const user = userEvent.setup();
    renderForm(order);

    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: order.id,
        senderName: undefined,
        destinationLocationId: undefined,
      }),
      expect.anything(),
    );
  });

  it('clears a previously-set senderName when the user blanks it out (explicit null)', async () => {
    const order: DeliveryOrderResponse = { ...mockOrders[0], senderName: 'Origin Warehouse Co' };
    const user = userEvent.setup();
    renderForm(order);

    await user.clear(screen.getByLabelText('Sender'));
    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: order.id, senderName: null }),
      expect.anything(),
    );
  });

  it('clears a previously-set destinationLocationId when the picker is cleared (explicit null)', async () => {
    const order: DeliveryOrderResponse = {
      ...mockOrders[0],
      destinationLocationId: 42,
      destinationLocationName: 'A-01-01',
    };
    const user = userEvent.setup();
    renderForm(order);

    await user.click(screen.getByRole('button', { name: /clear location/i }));
    await user.click(screen.getByTestId('order-form-submit'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: order.id, destinationLocationId: null }),
      expect.anything(),
    );
  });
});

describe('patchIdField (:1457 tri-state payload builder for id fields)', () => {
  it('omits when there was no original id and nothing was picked', () => {
    expect(patchIdField(null, null)).toBeUndefined();
    expect(patchIdField(undefined, null)).toBeUndefined();
  });

  it('sends explicit null when a previously-set id is cleared', () => {
    expect(patchIdField(5, null)).toBeNull();
  });

  it('sends the current id when set, whether new or unchanged from the original', () => {
    expect(patchIdField(null, 7)).toBe(7);
    expect(patchIdField(5, 7)).toBe(7);
    expect(patchIdField(5, 5)).toBe(5);
  });
});

describe('patchField (D2 tri-state payload builder)', () => {
  it('omits when there was no original value and nothing was typed', () => {
    expect(patchField(null, '')).toBeUndefined();
    expect(patchField(undefined, '')).toBeUndefined();
    expect(patchField(undefined, '   ')).toBeUndefined();
  });

  it('sends explicit null when a previously-set value is blanked out', () => {
    expect(patchField('original', '')).toBeNull();
    expect(patchField('original', '   ')).toBeNull();
  });

  it('sends the trimmed value when non-empty, regardless of the original', () => {
    expect(patchField(null, 'new value')).toBe('new value');
    expect(patchField('original', 'new value')).toBe('new value');
    expect(patchField('original', '  padded  ')).toBe('padded');
  });
});
