import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SectionCard } from '../section-card';
import { ClassChip } from '../class-chip';
import { StatusPill } from '../status-pill';
import { TrackTag } from '../track-tag';
import { AttributeGrid } from '../attribute-grid';

describe('SectionCard', () => {
  it('renders title, action slot, and children', () => {
    render(<SectionCard title="Stock position" action={<span>right</span>}><p>body</p></SectionCard>);
    expect(screen.getByText('Stock position')).toBeInTheDocument();
    expect(screen.getByText('right')).toBeInTheDocument();
    expect(screen.getByText('body')).toBeInTheDocument();
  });
});

describe('ClassChip', () => {
  it('renders the class letter for A/B/C', () => {
    render(<ClassChip cls="A" />); expect(screen.getByText('A')).toBeInTheDocument();
  });
  it('renders a neutral dash when class is null', () => {
    render(<ClassChip cls={null} />); expect(screen.getByText('—')).toBeInTheDocument();
  });
});

describe('TrackTag', () => {
  it('renders LPN when tracked, LOOSE otherwise', () => {
    const { rerender } = render(<TrackTag tracked />); expect(screen.getByText('LPN')).toBeInTheDocument();
    rerender(<TrackTag tracked={false} />); expect(screen.getByText('LOOSE')).toBeInTheDocument();
  });
});

describe('AttributeGrid', () => {
  it('renders label/value pairs and — for null values', () => {
    render(<AttributeGrid items={[{ label: 'Barcode', value: '123' }, { label: 'Weight', value: null }]} />);
    expect(screen.getByText('Barcode')).toBeInTheDocument();
    expect(screen.getByText('123')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});

describe('StatusPill', () => {
  it('renders its label', () => {
    render(<StatusPill label="Pickable" tone="lime" />); expect(screen.getByText('Pickable')).toBeInTheDocument();
  });
});
