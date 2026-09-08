import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ProductResponse } from '@/types/product';

// ItemFormSheet renders ProductForm, which depends on these hooks.
vi.mock('@/pages/products/use-products', () => ({
  useCreateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useUpdateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useAddBarcode: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useRemoveBarcode: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useItemUnits: vi.fn(() => ({ data: [{ id: 1, name: 'Piece', unitType: 'PIECE' }], isLoading: false })),
}));

import { ItemFormSheet } from '../item-form-sheet';

const mockProduct: ProductResponse = {
  id: 1,
  number: 'SKU-001',
  name: 'Widget A',
  description: null,
  state: 0,
  itemUnit: { id: 1, name: 'Piece', unitType: 'PIECE' },
  scale: 0,
  weight: null,
  height: null,
  width: null,
  depth: null,
  volume: null,
  lotMandatory: false,
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
};

describe('ItemFormSheet', () => {
  it('renders the ProductForm SKU field when open in edit mode', () => {
    render(<ItemFormSheet open product={mockProduct} onOpenChange={vi.fn()} />);

    expect(screen.getByLabelText(/sku/i)).toBeInTheDocument();
  });

  it('renders the ProductForm SKU field when open in create mode (no product)', () => {
    render(<ItemFormSheet open onOpenChange={vi.fn()} />);

    expect(screen.getByLabelText(/sku/i)).toBeInTheDocument();
  });

  it('does not render the form when closed', () => {
    render(<ItemFormSheet open={false} onOpenChange={vi.fn()} />);

    expect(screen.queryByLabelText(/sku/i)).not.toBeInTheDocument();
  });
});
