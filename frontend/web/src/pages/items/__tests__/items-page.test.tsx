import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router';
import userEvent from '@testing-library/user-event';
import type { PaginatedResponse } from '@/types/api';
import type { ProductResponse } from '@/types/product';

// ItemsPage's create sheet renders ProductForm (via ItemFormSheet), which
// depends on these hooks too (see item-form-sheet.test.tsx for the same mock).
vi.mock('@/pages/products/use-products', () => ({
  useProducts: vi.fn(),
  useCreateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useUpdateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useAddBarcode: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useRemoveBarcode: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useItemUnits: vi.fn(() => ({ data: [{ id: 1, name: 'Piece', unitType: 'PIECE' }], isLoading: false })),
}));

vi.mock('../use-item-stock', () => ({
  useItemStock: vi.fn(() => ({ stockByItem: new Map(), isLoading: false })),
}));

vi.mock('../use-item-analytics', () => ({
  useItemAnalytics: vi.fn(() => ({
    forecastBySku: new Map(),
    slottingBySku: new Map(),
    forecastEntitled: false,
    slottingEntitled: false,
    forecastLoading: false,
    slottingLoading: false,
  })),
}));

const mockProducts: ProductResponse[] = [
  {
    id: 1,
    number: 'SKU-001',
    name: 'Widget A',
    description: 'A test widget',
    state: 0,
    itemUnit: { id: 1, name: 'Piece', unitType: 'PIECE' },
    scale: 0,
    weight: 1.5,
    height: null,
    width: null,
    depth: null,
    volume: null,
    lotMandatory: true,
    bestBeforeMandatory: false,
    shelflife: null,
    serialNoRecordType: 'NO_RECORD',
    defaultUnitLoadTypeId: null,
    defaultPackagingUnitId: null,
    defaultStorageStrategyId: null,
    zoneId: null,
    tradeGroup: null,
    imageUrl: null,
    numbers: [],
    packagingUnits: [],
    created: '2026-03-01T00:00:00Z',
    modified: '2026-03-01T00:00:00Z',
  },
];

const mockPaginatedResponse: PaginatedResponse<ProductResponse> = {
  content: mockProducts,
  page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
};

import { useProducts } from '@/pages/products/use-products';
import { ItemsPage } from '../items-page';

beforeEach(() => {
  vi.mocked(useProducts).mockReturnValue({
    data: mockPaginatedResponse,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useProducts>);
});

describe('ItemsPage', () => {
  it('renders the All / Class A / Class B / Class C filter chips', () => {
    render(
      <MemoryRouter>
        <ItemsPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Class A' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Class B' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Class C' })).toBeInTheDocument();
  });

  it('does not render the old Below-reorder / Re-slot-flagged filters', () => {
    render(
      <MemoryRouter>
        <ItemsPage />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('button', { name: 'Below reorder' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Re-slot flagged' })).not.toBeInTheDocument();
  });

  // ⌘K "Create product" deep-link (Task 6): /items?create=1 on a fresh mount
  // must open the create form (formProduct initializer reads the param).
  // The sheet's title text ("New item") collides with the always-present
  // "New item" toolbar button, so scope to the heading role (Radix
  // Dialog.Title renders an <h2>) rather than getByText.
  it('opens the create form when navigated with ?create=1 (⌘K deep link)', () => {
    render(
      <MemoryRouter initialEntries={['/items?create=1']}>
        <ItemsPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: 'New item' })).toBeInTheDocument();
  });

  // ⌘K "Create product" while ALREADY on /items: the useState initializer
  // never re-runs, so the searchParams effect must apply the param
  // (TasksPage/UsersPage idiom).
  it('applies a ?create=1 param pushed while the page stays mounted', async () => {
    const user = userEvent.setup();
    function Harness() {
      const navigate = useNavigate();
      return (
        <>
          <button data-testid="nav-create" onClick={() => navigate('/items?create=1')}>
            nav
          </button>
          <ItemsPage />
        </>
      );
    }
    render(
      <MemoryRouter initialEntries={['/items']}>
        <Harness />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('heading', { name: 'New item' })).not.toBeInTheDocument();

    await user.click(screen.getByTestId('nav-create'));

    expect(await screen.findByRole('heading', { name: 'New item' })).toBeInTheDocument();
  });
});
