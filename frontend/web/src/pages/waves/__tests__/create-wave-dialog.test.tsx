import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

vi.mock('@/pages/orders/use-orders', () => ({
  useOrderStrategies: vi.fn(() => ({ data: [{ id: 1, name: 'Default' }], isLoading: false })),
}));

const useCreateWave = vi.fn();
const useSelectionRules = vi.fn();
vi.mock('@/features/waves/use-waves', () => ({
  useCreateWave: (...args: unknown[]) => useCreateWave(...args),
  useSelectionRules: (...args: unknown[]) => useSelectionRules(...args),
}));

const { CreateWaveDialog } = await import('../create-wave-dialog');

let createMutate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  createMutate = vi.fn();
  useCreateWave.mockReturnValue({ mutate: createMutate, isPending: false });
  useSelectionRules.mockReturnValue({
    data: {
      content: [{ id: 5, name: 'Rush orders', description: null, definition: { combinator: 'AND', conditions: [], groups: [] }, boundByStrategies: 0, created: '2026-08-21T00:00:00Z' }],
      page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
    },
  });
});

function renderDialog() {
  return render(<CreateWaveDialog open onOpenChange={vi.fn()} />);
}

describe('CreateWaveDialog -- Selection section', () => {
  it('shows no rule select before a selection strategy is chosen', () => {
    renderDialog();
    expect(screen.queryByTestId('wave-selection-rule-select')).not.toBeInTheDocument();
  });

  it('shows no rule select for the due-date-priority strategy', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByTestId('wave-selection-strategy-select'));
    await user.click(await screen.findByText('due-date-priority'));

    expect(screen.queryByTestId('wave-selection-rule-select')).not.toBeInTheDocument();
  });

  it('shows the rule select only once rule-based is chosen, populated from the rules list', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByTestId('wave-selection-strategy-select'));
    await user.click(await screen.findByText('rule-based'));

    expect(screen.getByTestId('wave-selection-rule-select')).toBeInTheDocument();
    await user.click(screen.getByTestId('wave-selection-rule-select'));
    expect(await screen.findByText('Rush orders')).toBeInTheDocument();
  });

  it('sends selectionStrategy and selectionRuleId in the create payload for a rule-based wave', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByTestId('wave-strategy-select'));
    await user.click(await screen.findByText('Default'));

    await user.click(screen.getByTestId('wave-selection-strategy-select'));
    await user.click(await screen.findByText('rule-based'));
    await user.click(screen.getByTestId('wave-selection-rule-select'));
    await user.click(await screen.findByText('Rush orders'));

    await user.click(screen.getByTestId('wave-create-submit'));

    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({ selectionStrategy: 'rule-based', selectionRuleId: 5 }),
      expect.anything(),
    );
  });

  it('omits selectionRuleId when no override strategy is chosen', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByTestId('wave-strategy-select'));
    await user.click(await screen.findByText('Default'));
    await user.click(screen.getByTestId('wave-create-submit'));

    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({ selectionStrategy: undefined, selectionRuleId: undefined }),
      expect.anything(),
    );
  });
});
