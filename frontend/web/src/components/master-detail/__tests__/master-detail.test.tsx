import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, beforeEach } from 'vitest';
import { MasterList, MasterListRow } from '../master-detail';

function rows(n: number) {
  return Array.from({ length: n }, (_, i) => (
    <MasterListRow key={i} tone="grey" active={false} onClick={() => {}}>
      <span>row-{i}</span>
    </MasterListRow>
  ));
}

function renderList(n: number) {
  return render(
    <MasterList searchValue="" onSearchChange={() => {}}>
      {rows(n)}
    </MasterList>,
  );
}

describe('MasterList row cap', () => {
  beforeEach(() => localStorage.clear());

  it('caps a long list to the default 50 rows and reports the truncation', () => {
    renderList(60);
    expect(screen.getAllByText(/^row-\d+$/)).toHaveLength(50);
    const cap = screen.getByTestId('master-list-rowcap');
    expect(cap).toHaveTextContent('Showing');
    expect(cap).toHaveTextContent('50');
    expect(cap).toHaveTextContent('60');
  });

  it('does not render the row-cap control for short lists', () => {
    renderList(10);
    expect(screen.getAllByText(/^row-\d+$/)).toHaveLength(10);
    expect(screen.queryByTestId('master-list-rowcap')).toBeNull();
  });

  it('shows all rows when the "All" limit is chosen, and persists it', () => {
    renderList(120);
    // default 50 shown
    expect(screen.getAllByText(/^row-\d+$/)).toHaveLength(50);
    const cap = screen.getByTestId('master-list-rowcap');
    fireEvent.click(within(cap).getByText('All'));
    expect(screen.getAllByText(/^row-\d+$/)).toHaveLength(120);
    expect(localStorage.getItem('karyo:list-row-limit')).toBe('Infinity');
  });

  it('honors a stored limit on mount', () => {
    localStorage.setItem('karyo:list-row-limit', '25');
    renderList(60);
    expect(screen.getAllByText(/^row-\d+$/)).toHaveLength(25);
  });
});
