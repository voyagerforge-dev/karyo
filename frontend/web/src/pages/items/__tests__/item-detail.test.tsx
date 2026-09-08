import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ItemDetail } from '../item-detail';
import type { ItemView } from '../item-model';

/**
 * Fixture: real stock across two locations, but NO forecast and NO slotting
 * (both paid engines absent/unlicensed-shaped) — the exact "honest gap" case
 * this test locks in. All numbers are distinct so text queries stay
 * unambiguous.
 */
const baseItem: ItemView = {
  id: 1,
  sku: 'SKU-001',
  name: 'Widget A',
  category: 'Fasteners',
  uom: 'Piece',
  barcode: '0123456789',
  pack: '12 / case',
  weight: '1.5 kg',
  dims: '10×10×10 cm',
  lotTracked: 'No',
  stock: {
    onHand: 100,
    available: 55,
    reserved: 25,
    held: 20,
    locations: [
      { location: 'A-01-02', lpn: 'UL-1001', amount: 60, state: 'ON_STOCK' },
      { location: 'B-04-01', lpn: 'UL-1002', amount: 40, state: 'ON_STOCK' },
    ],
  },
  forecast: null,
  slotting: null,
  cls: null,
};

const defaultProps = {
  forecastEntitled: true,
  slottingEntitled: true,
  stockLoading: false,
  forecastLoading: false,
  slottingLoading: false,
  onEdit: vi.fn(),
};

describe('ItemDetail', () => {
  it('renders every section with honest gaps when stock exists but forecast/slotting are absent', () => {
    render(<ItemDetail item={baseItem} {...defaultProps} />);

    // Header
    const heading = screen.getByRole('heading', { level: 1, name: 'Widget A' });
    expect(heading).toBeInTheDocument();
    // Neutral class chip (no slotting) — scoped to the header row so it
    // can't be confused with any other empty-state dash on the page.
    const headerRow = heading.closest('div');
    expect(headerRow).not.toBeNull();
    expect(within(headerRow as HTMLElement).getByText('—')).toBeInTheDocument();

    // Stock position — section present, real values
    expect(screen.getByText('Stock position')).toBeInTheDocument();
    expect(screen.getByText('across 2 locations')).toBeInTheDocument();
    expect(screen.getByText('100')).toBeInTheDocument(); // on-hand total

    // Where it's stored — section present, real rows
    expect(screen.getByText("Where it's stored")).toBeInTheDocument();
    expect(screen.getByText('A-01-02')).toBeInTheDocument();
    expect(screen.getByText('UL-1001')).toBeInTheDocument();
    expect(screen.getByText('B-04-01')).toBeInTheDocument();

    // Demand · 30 days — section stays, honest empty-state (no forecast)
    expect(screen.getByText('Demand · 30 days')).toBeInTheDocument();
    expect(screen.getByText('No demand history yet')).toBeInTheDocument();

    // Attributes — section present
    expect(screen.getByText('Attributes')).toBeInTheDocument();

    // Inbound / Open demand — sections stay, honest "None" empty-states
    expect(screen.getByText('Inbound')).toBeInTheDocument();
    expect(screen.getByText('Open demand')).toBeInTheDocument();
    expect(screen.getAllByText('None')).toHaveLength(2);
  });

  it('calls onEdit when the header Edit button is clicked', async () => {
    const onEdit = vi.fn();
    const user = userEvent.setup();
    render(<ItemDetail item={baseItem} {...defaultProps} onEdit={onEdit} />);

    await user.click(screen.getByRole('button', { name: 'Edit' }));

    expect(onEdit).toHaveBeenCalledTimes(1);
  });
});
