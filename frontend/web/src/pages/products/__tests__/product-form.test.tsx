import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const addBarcodeMutate = vi.fn();

// Mock hooks
vi.mock('../use-products', () => ({
  useCreateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useUpdateProduct: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useAddBarcode: vi.fn(() => ({ mutate: addBarcodeMutate, isPending: false })),
  useRemoveBarcode: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useItemUnits: vi.fn(() => ({ data: [{ id: 1, name: 'Piece', unitType: 'PIECE' }], isLoading: false })),
}));

import { ProductForm } from '../product-form';
import type { ProductResponse } from '@/types/product';

const mockOnClose = vi.fn();

function buildProduct(overrides: Partial<ProductResponse> = {}): ProductResponse {
  return {
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
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ProductForm', () => {
  it('renders required fields (SKU and Name) in create mode', () => {
    render(<ProductForm onClose={mockOnClose} />);

    expect(screen.getByLabelText(/sku/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^name/i)).toBeInTheDocument();
  });

  it('shows validation errors when submitting empty required fields', async () => {
    const user = userEvent.setup();
    render(<ProductForm onClose={mockOnClose} />);

    // Try to submit without filling required fields
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(screen.getByText(/sku is required/i)).toBeInTheDocument();
    expect(screen.getByText(/name is required/i)).toBeInTheDocument();
  });

  it('does NOT render barcode section in create mode', () => {
    render(<ProductForm onClose={mockOnClose} />);

    expect(screen.queryByText(/barcodes/i)).not.toBeInTheDocument();
  });

  it('renders barcode section in edit mode', () => {
    const product: ProductResponse = {
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
      numbers: [
        { id: 10, number: 'BC-001', numberType: 'EAN13', packagingUnitId: null, manufacturerName: null },
      ],
      packagingUnits: [],
      created: '2026-03-01T00:00:00Z',
      modified: '2026-03-01T00:00:00Z',
    };

    render(<ProductForm product={product} onClose={mockOnClose} />);

    expect(screen.getByText(/barcodes/i)).toBeInTheDocument();
    expect(screen.getByText('BC-001')).toBeInTheDocument();
  });

  it('offers default packaging unit only in edit mode, from the product\'s own units', async () => {
    const user = userEvent.setup();
    const product = buildProduct({
      packagingUnits: [
        { id: 7, name: 'Case', amount: 12, itemUnitName: 'Piece', weight: null, height: null, width: null, depth: null, packingLevel: 1 },
      ],
    });

    render(<ProductForm product={product} onClose={mockOnClose} />);

    const select = screen.getByLabelText('Default packaging unit');
    await user.click(select);
    expect(await screen.findByText(/Case/)).toBeInTheDocument();
  });

  it('omits the default packaging unit field on create', () => {
    render(<ProductForm onClose={mockOnClose} />);

    expect(screen.queryByLabelText('Default packaging unit')).not.toBeInTheDocument();
  });

  it('sends manufacturerName when adding a barcode', async () => {
    const user = userEvent.setup();
    const product = buildProduct();

    render(<ProductForm product={product} onClose={mockOnClose} />);

    await user.type(screen.getByLabelText('Manufacturer'), 'Acme Corp');
    await user.type(screen.getByLabelText(/number/i), 'BC-999');
    await user.click(screen.getByRole('button', { name: /add/i }));

    expect(addBarcodeMutate).toHaveBeenCalledWith(
      expect.objectContaining({ manufacturerName: 'Acme Corp' }),
      expect.anything(),
    );
  });
});
