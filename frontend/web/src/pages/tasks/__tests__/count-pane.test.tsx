import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { WorkItemResponse } from '@/types/work';
import { CountPane } from '../count-pane';

const countItem: WorkItemResponse = {
  ref: 'COUNT:7',
  workType: 'COUNT',
  priority: 80,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'CC-01',
  destination: null,
  summary: 'Cycle count CC-01',
  createdAt: '2026-06-13T09:10:00Z',
};

function renderPane() {
  return render(
    <MemoryRouter>
      <CountPane workItem={countItem} />
    </MemoryRouter>,
  );
}

describe('CountPane', () => {
  it('shows the count summary and deep-links to cycle count', () => {
    renderPane();

    expect(screen.getByText('CC-01')).toBeInTheDocument();
    expect(screen.getByText('Cycle count CC-01')).toBeInTheDocument();

    const link = screen.getByTestId('count-open-cycle-count');
    expect(link).toHaveAttribute('href', '/cycle-count?order=7');
  });
});
