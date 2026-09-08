import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { StackedBar } from '../stacked-bar';
import { DemandSparkbars } from '../demand-sparkbars';

describe('StackedBar', () => {
  it('renders one segment per entry with title = label', () => {
    render(<StackedBar segments={[{ value: 80, color: '#C7F24E', label: 'Available' }, { value: 20, color: '#F0B43C', label: 'Allocated' }]} />);
    expect(screen.getByTitle('Available')).toBeInTheDocument();
    expect(screen.getByTitle('Allocated')).toBeInTheDocument();
  });
});

describe('DemandSparkbars', () => {
  it('renders bars when given data', () => {
    const { container } = render(<DemandSparkbars bars={[{ pct: 50, color: '#C7F24E' }, { pct: 80, color: '#C7F24E' }]} />);
    expect(container.querySelectorAll('[data-bar]').length).toBe(2);
  });
  it('renders an empty-state when bars is null', () => {
    render(<DemandSparkbars bars={null} />);
    expect(screen.getByText(/no demand history/i)).toBeInTheDocument();
  });
});
