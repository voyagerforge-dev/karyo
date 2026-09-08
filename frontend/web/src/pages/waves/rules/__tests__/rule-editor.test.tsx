import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SelectionFieldResponse, SelectionRuleResponse } from '@/types/waves';
import { instantToDatetimeLocal } from '../rule-model';

// Radix Select/Switch need these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    problem: { detail: string };
    constructor(detail: string) {
      super(detail);
      this.problem = { detail };
    }
  }
  return { FakeApiError };
});

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  ApiError: FakeApiError,
}));

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const useSelectionRuleFields = vi.fn();
const useCreateSelectionRule = vi.fn();
const useUpdateSelectionRule = vi.fn();
const usePreviewSelectionRule = vi.fn();
vi.mock('@/features/waves/use-waves', () => ({
  useSelectionRuleFields: (...args: unknown[]) => useSelectionRuleFields(...args),
  useCreateSelectionRule: (...args: unknown[]) => useCreateSelectionRule(...args),
  useUpdateSelectionRule: (...args: unknown[]) => useUpdateSelectionRule(...args),
  usePreviewSelectionRule: (...args: unknown[]) => usePreviewSelectionRule(...args),
}));

const { RuleEditor } = await import('../rule-editor');

const FIELDS: SelectionFieldResponse[] = [
  {
    field: 'deliveryDate',
    type: 'DATE',
    label: 'Delivery date',
    ops: ['eq', 'lt', 'lte', 'gt', 'gte', 'between', 'isNull', 'notNull'],
  },
  { field: 'prio', type: 'NUMBER', label: 'Priority', ops: ['eq', 'lt', 'lte', 'gt', 'gte', 'between'] },
  {
    field: 'customerName',
    type: 'STRING',
    label: 'Customer',
    ops: ['eq', 'neq', 'in', 'like', 'isNull', 'notNull'],
  },
  {
    field: 'created',
    type: 'DATETIME',
    label: 'Created',
    ops: ['lt', 'lte', 'gt', 'gte', 'between'],
  },
];

const SAVED_RULE: SelectionRuleResponse = {
  id: 1,
  name: 'Rush orders',
  description: null,
  definition: { combinator: 'AND', conditions: [{ field: 'prio', op: 'gte', value: 80 }], groups: [] },
  boundByStrategies: 0,
  created: '2026-08-21T00:00:00Z',
};

function ruleWithNConditions(n: number): SelectionRuleResponse {
  return {
    ...SAVED_RULE,
    definition: {
      combinator: 'AND',
      conditions: Array.from({ length: n }, (_, i) => ({ field: 'prio', op: 'eq', value: i })),
      groups: [],
    },
  };
}

let createMutateAsync: ReturnType<typeof vi.fn>;
let updateMutateAsync: ReturnType<typeof vi.fn>;
let previewMutate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  useSelectionRuleFields.mockReturnValue({ data: FIELDS, isLoading: false });
  createMutateAsync = vi.fn().mockResolvedValue(SAVED_RULE);
  updateMutateAsync = vi.fn().mockResolvedValue(SAVED_RULE);
  previewMutate = vi.fn();
  useCreateSelectionRule.mockReturnValue({ mutateAsync: createMutateAsync, isPending: false });
  useUpdateSelectionRule.mockReturnValue({ mutateAsync: updateMutateAsync, isPending: false });
  usePreviewSelectionRule.mockReturnValue({ mutate: previewMutate, isPending: false, data: undefined });
});

describe('RuleEditor -- registry-driven ops', () => {
  it("shows only the selected NUMBER field's own ops, not a hardcoded set", async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByText('Priority'));

    await user.click(screen.getByTestId('rule-condition-0-op'));
    expect(screen.getByText('between')).toBeInTheDocument();
    expect(screen.getByText('gte')).toBeInTheDocument();
    expect(screen.queryByText('in')).not.toBeInTheDocument();
    expect(screen.queryByText('isNull')).not.toBeInTheDocument();
  });

  it("shows only the selected STRING field's own ops (including notNull)", async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByText('Customer'));

    await user.click(screen.getByTestId('rule-condition-0-op'));
    expect(screen.getByText('in')).toBeInTheDocument();
    expect(screen.getByText('notNull')).toBeInTheDocument();
    expect(screen.queryByText('between')).not.toBeInTheDocument();
  });
});

describe('RuleEditor -- 20-condition cap', () => {
  it('disables Add condition once the rule already has 20 conditions', () => {
    render(<RuleEditor rule={ruleWithNConditions(20)} canWrite onSaved={vi.fn()} />);
    expect(screen.getByTestId('rule-add-condition')).toBeDisabled();
  });

  it('leaves Add condition enabled below the cap', () => {
    render(<RuleEditor rule={ruleWithNConditions(19)} canWrite onSaved={vi.fn()} />);
    expect(screen.getByTestId('rule-add-condition')).toBeEnabled();
  });
});

describe('RuleEditor -- depth cap (no Add group inside a group)', () => {
  it('offers Add group at the top level but not inside a nested group', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    expect(screen.getByTestId('rule-add-group')).toBeInTheDocument();
    await user.click(screen.getByTestId('rule-add-group'));

    expect(screen.getByTestId('rule-group-0-level')).toBeInTheDocument();
    expect(screen.queryByTestId('rule-group-0-add-group')).not.toBeInTheDocument();
  });
});

describe('RuleEditor -- value controls by type', () => {
  it('isNull renders no value control', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByText('Customer'));
    await user.click(screen.getByTestId('rule-condition-0-op'));
    await user.click(await screen.findByText('isNull'));

    const row = screen.getByTestId('rule-condition-0');
    expect(row.querySelector('[data-testid^="rule-condition-0-value"]')).toBeNull();
  });

  it('between renders two value inputs', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByText('Priority'));
    await user.click(screen.getByTestId('rule-condition-0-op'));
    await user.click(await screen.findByText('between'));

    expect(screen.getByTestId('rule-condition-0-value-lo')).toBeInTheDocument();
    expect(screen.getByTestId('rule-condition-0-value-hi')).toBeInTheDocument();
  });

  it('a relative DATE toggle produces a TODAY+N literal on save', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByRole('option', { name: 'Delivery date' }));
    await user.click(screen.getByTestId('rule-condition-0-op'));
    await user.click(await screen.findByText('eq'));

    await user.click(screen.getByTestId('rule-condition-0-value-relative-toggle'));
    const offsetInput = screen.getByTestId('rule-condition-0-value-relative-offset');
    await user.clear(offsetInput);
    await user.type(offsetInput, '5');

    await user.type(screen.getByTestId('rule-name-input'), 'Due soon');
    await user.click(screen.getByTestId('rule-save-button'));

    expect(createMutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({
        definition: expect.objectContaining({
          conditions: [expect.objectContaining({ field: 'deliveryDate', op: 'eq', value: 'TODAY+5' })],
        }),
      }),
    );
  });

  it('a DATETIME condition value is emitted as a strict, parseable Instant string (ends with Z)', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByRole('option', { name: 'Created' }));
    await user.click(screen.getByTestId('rule-condition-0-op'));
    await user.click(await screen.findByText('gte'));

    // datetime-local inputs don't accept realistic keystrokes from userEvent.type -- set the
    // raw local value directly, as the browser control itself would emit on change.
    fireEvent.change(screen.getByTestId('rule-condition-0-value'), {
      target: { value: '2026-08-21T10:30' },
    });

    await user.type(screen.getByTestId('rule-name-input'), 'Recent orders');
    await user.click(screen.getByTestId('rule-save-button'));

    expect(createMutateAsync).toHaveBeenCalledTimes(1);
    const body = createMutateAsync.mock.calls[0][0] as {
      definition: { conditions: Array<{ field: string; op: string; value: unknown }> };
    };
    const condition = body.definition.conditions[0];
    expect(condition).toMatchObject({ field: 'created', op: 'gte' });
    expect(condition.value).toEqual(expect.stringMatching(/Z$/));
    expect(Number.isNaN(new Date(condition.value as string).getTime())).toBe(false);
  });

  it('editing a saved DATETIME condition round-trips the Instant value into the datetime-local display', () => {
    const instantValue = '2026-08-21T10:30:00.000Z';
    const rule: SelectionRuleResponse = {
      ...SAVED_RULE,
      definition: {
        combinator: 'AND',
        conditions: [{ field: 'created', op: 'gte', value: instantValue }],
        groups: [],
      },
    };
    render(<RuleEditor rule={rule} canWrite onSaved={vi.fn()} />);

    expect(screen.getByTestId('rule-condition-0-value')).toHaveValue(
      instantToDatetimeLocal(instantValue),
    );
  });
});

describe('RuleEditor -- save validation and server error surfacing', () => {
  it('blocks save client-side when a condition is incomplete', async () => {
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.type(screen.getByTestId('rule-name-input'), 'Incomplete rule');
    await user.click(screen.getByTestId('rule-save-button'));

    expect(screen.getByTestId('rule-save-errors')).toBeInTheDocument();
    expect(createMutateAsync).not.toHaveBeenCalled();
  });

  it('surfaces a 422 ProblemDetail near the builder, split per condition', async () => {
    createMutateAsync.mockRejectedValueOnce(
      new FakeApiError("op 'eq' on field 'prio' requires a value; unknown field 'bogus'"),
    );
    const user = userEvent.setup();
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-add-condition'));
    await user.click(screen.getByTestId('rule-condition-0-field'));
    await user.click(await screen.findByText('Priority'));
    await user.click(screen.getByTestId('rule-condition-0-op'));
    await user.click(await screen.findByText('gte'));
    await user.type(screen.getByTestId('rule-condition-0-value'), '80');
    await user.type(screen.getByTestId('rule-name-input'), 'Rush orders');

    await user.click(screen.getByTestId('rule-save-button'));

    const errors = await screen.findByTestId('rule-save-errors');
    expect(errors).toHaveTextContent(/op 'eq' on field 'prio' requires a value/);
    expect(errors).toHaveTextContent(/unknown field 'bogus'/);
  });
});

describe('RuleEditor -- preview', () => {
  it('disables Preview with an explanatory title until the rule is saved', () => {
    render(<RuleEditor rule={null} canWrite onSaved={vi.fn()} />);
    const button = screen.getByTestId('rule-preview-button');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', expect.stringMatching(/save/i));
  });

  it('previews the saved rule id and renders the match summary', async () => {
    usePreviewSelectionRule.mockReturnValue({
      mutate: previewMutate,
      isPending: false,
      data: { matchedCount: 3, poolSize: 12, sample: [] },
    });
    const user = userEvent.setup();
    render(<RuleEditor rule={SAVED_RULE} canWrite onSaved={vi.fn()} />);

    const button = screen.getByTestId('rule-preview-button');
    expect(button).toBeEnabled();
    await user.click(button);

    expect(previewMutate).toHaveBeenCalledWith(1);
    expect(screen.getByTestId('rule-preview-result')).toHaveTextContent('3 of 12');
  });

  it('renders each sample row with its delivery date (FOLD-6)', async () => {
    usePreviewSelectionRule.mockReturnValue({
      mutate: previewMutate,
      isPending: false,
      data: {
        matchedCount: 1,
        poolSize: 1,
        sample: [
          {
            orderId: 42,
            orderNumber: 'DO-42',
            state: 'PROCESSABLE',
            prio: 50,
            customerName: 'Acme',
            deliveryDate: '2026-08-25',
          },
        ],
      },
    });
    const user = userEvent.setup();
    render(<RuleEditor rule={SAVED_RULE} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-preview-button'));

    expect(screen.getByTestId('rule-preview-result')).toHaveTextContent('DO-42 · Acme · 2026-08-25');
  });

  it('shows an em dash when a sample row has no delivery date (FOLD-6)', async () => {
    usePreviewSelectionRule.mockReturnValue({
      mutate: previewMutate,
      isPending: false,
      data: {
        matchedCount: 1,
        poolSize: 1,
        sample: [
          {
            orderId: 43,
            orderNumber: 'DO-43',
            state: 'PROCESSABLE',
            prio: 50,
            customerName: null,
            deliveryDate: null,
          },
        ],
      },
    });
    const user = userEvent.setup();
    render(<RuleEditor rule={SAVED_RULE} canWrite onSaved={vi.fn()} />);

    await user.click(screen.getByTestId('rule-preview-button'));

    expect(screen.getByTestId('rule-preview-result')).toHaveTextContent('DO-43 · — · —');
  });
});
