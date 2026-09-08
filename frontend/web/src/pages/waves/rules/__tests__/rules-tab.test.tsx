import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SelectionRuleResponse } from '@/types/waves';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  ApiError: class ApiError extends Error {},
}));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const useSelectionRules = vi.fn();
const useDeleteSelectionRule = vi.fn();
const useSelectionRuleFields = vi.fn();
const useCreateSelectionRule = vi.fn();
const useUpdateSelectionRule = vi.fn();
const usePreviewSelectionRule = vi.fn();
vi.mock('@/features/waves/use-waves', () => ({
  useSelectionRules: (...args: unknown[]) => useSelectionRules(...args),
  useDeleteSelectionRule: (...args: unknown[]) => useDeleteSelectionRule(...args),
  useSelectionRuleFields: (...args: unknown[]) => useSelectionRuleFields(...args),
  useCreateSelectionRule: (...args: unknown[]) => useCreateSelectionRule(...args),
  useUpdateSelectionRule: (...args: unknown[]) => useUpdateSelectionRule(...args),
  usePreviewSelectionRule: (...args: unknown[]) => usePreviewSelectionRule(...args),
}));

const { RulesTab } = await import('../rules-tab');

const UNBOUND: SelectionRuleResponse = {
  id: 1,
  name: 'Rush orders',
  description: null,
  definition: { combinator: 'AND', conditions: [{ field: 'prio', op: 'gte', value: 80 }], groups: [] },
  boundByStrategies: 0,
  created: '2026-08-21T00:00:00Z',
};

const BOUND: SelectionRuleResponse = {
  id: 2,
  name: 'EU customers',
  description: null,
  definition: { combinator: 'AND', conditions: [{ field: 'country', op: 'eq', value: 'DE' }], groups: [] },
  boundByStrategies: 2,
  created: '2026-08-21T00:00:00Z',
};

let deleteMutate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  useSelectionRules.mockReturnValue({
    data: { content: [UNBOUND, BOUND], page: { number: 0, size: 50, totalElements: 2, totalPages: 1 } },
    isLoading: false,
  });
  deleteMutate = vi.fn();
  useDeleteSelectionRule.mockReturnValue({ mutate: deleteMutate, isPending: false });
  useSelectionRuleFields.mockReturnValue({ data: [], isLoading: false });
  useCreateSelectionRule.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  useUpdateSelectionRule.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  usePreviewSelectionRule.mockReturnValue({ mutate: vi.fn(), isPending: false, data: undefined });
});

describe('RulesTab -- bound badge and delete gating', () => {
  it('renders a Bound badge and disables delete for a rule bound by a strategy', () => {
    render(<RulesTab canWrite />);

    expect(screen.getByTestId('rule-bound-badge-2')).toHaveTextContent('Bound · 2');
    expect(screen.getByTestId('rule-delete-2')).toHaveAttribute('aria-disabled', 'true');
  });

  it('leaves delete enabled and shows no Bound badge for an unbound rule', () => {
    render(<RulesTab canWrite />);

    expect(screen.queryByTestId('rule-bound-badge-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('rule-delete-1')).toHaveAttribute('aria-disabled', 'false');
  });

  it('clicking delete on a bound rule does not open the confirm dialog', async () => {
    const user = userEvent.setup();
    render(<RulesTab canWrite />);

    await user.click(screen.getByTestId('rule-delete-2'));
    expect(screen.queryByTestId('rule-delete-confirm-btn')).not.toBeInTheDocument();
  });

  it('deleting an unbound rule opens a confirm dialog and mutates on confirm', async () => {
    const user = userEvent.setup();
    render(<RulesTab canWrite />);

    await user.click(screen.getByTestId('rule-delete-1'));
    await user.click(screen.getByTestId('rule-delete-confirm-btn'));

    expect(deleteMutate).toHaveBeenCalledWith(1, expect.anything());
  });
});

describe('RulesTab -- selection and create', () => {
  it('selecting a rule row shows the editor with that rule loaded', async () => {
    const user = userEvent.setup();
    render(<RulesTab canWrite />);

    expect(screen.queryByTestId('rule-editor')).not.toBeInTheDocument();
    await user.click(screen.getByText('Rush orders'));
    expect(screen.getByTestId('rule-editor')).toBeInTheDocument();
    expect(screen.getByTestId('rule-name-input')).toHaveValue('Rush orders');
  });

  it('New rule opens an empty editor', async () => {
    const user = userEvent.setup();
    render(<RulesTab canWrite />);

    await user.click(screen.getByTestId('new-rule-button'));
    expect(screen.getByTestId('rule-editor')).toBeInTheDocument();
    expect(screen.getByTestId('rule-name-input')).toHaveValue('');
  });
});
