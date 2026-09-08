import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useCopilot } = await import('@/features/copilot/use-copilot');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useCopilot', () => {
  beforeEach(() => vi.clearAllMocks());

  it('appends user + assistant messages and surfaces a proposal', async () => {
    mockApi.post.mockResolvedValue({
      message: 'Prepared: receive 100',
      proposal: { id: 'p1', summary: 'Receive 100 × DEMO-MOUSE', toolName: 'receiveStock' },
    });
    const { result } = renderHook(() => useCopilot(), { wrapper: wrapper() });
    await act(async () => {
      await result.current.send('receive 100 mice');
    });
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
    expect(result.current.messages[0]).toMatchObject({ role: 'user', text: 'receive 100 mice' });
    expect(result.current.messages[1].proposal?.id).toBe('p1');
    expect(mockApi.post).toHaveBeenCalledWith(
      '/api/v1/ai/chat',
      expect.objectContaining({ message: 'receive 100 mice' }),
    );
  });

  it('confirm() on error appends a graceful assistant message and does not throw', async () => {
    // First set up a chat with a proposal
    mockApi.post.mockResolvedValueOnce({
      message: 'Ready to move stock',
      proposal: { id: 'p2', summary: 'Move 10 × SKU-X', toolName: 'moveStock' },
    });
    const { result } = renderHook(() => useCopilot(), { wrapper: wrapper() });
    await act(async () => {
      await result.current.send('move 10 sku-x');
    });
    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    // Now confirm fails with a meaningful error
    const apiError = new Error('Insufficient permissions to perform this action');
    mockApi.post.mockRejectedValueOnce(apiError);

    await act(async () => {
      await result.current.confirm('p2');
    });

    // Should have 3 messages: user + assistant-with-proposal + graceful-error-assistant
    await waitFor(() => expect(result.current.messages).toHaveLength(3));
    expect(result.current.messages[2]).toMatchObject({
      role: 'assistant',
      text: 'Insufficient permissions to perform this action',
    });
    // proposal on the prior message should be cleared
    expect(result.current.messages[1].proposal).toBeNull();
    // isSending should be false
    expect(result.current.isSending).toBe(false);
  });
});
