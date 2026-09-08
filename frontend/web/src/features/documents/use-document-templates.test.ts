import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { DocumentTemplateResponse } from '@/types/document';

const mockApi = { get: vi.fn(), post: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const {
  useDocumentTemplates,
  useUploadDocumentTemplate,
  useActivateDocumentTemplate,
  useDeleteDocumentTemplate,
} = await import('./use-document-templates');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    Wrapper: ({ children }: { children: React.ReactNode }) =>
      createElement(QueryClientProvider, { client: qc }, children),
    qc,
  };
}

const TEMPLATE: DocumentTemplateResponse = {
  id: 1,
  clientId: 1,
  templatePath: '/templates/delivery-note.html',
  templateVersion: 1,
  active: true,
  content: '<html>hi</html>',
  created: '2026-07-25T10:00:00Z',
  modified: '2026-07-25T10:00:00Z',
};

describe('useDocumentTemplates', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue([TEMPLATE]);
  });

  it('fetches GET /api/v1/document-templates with clientId + templatePath query params', async () => {
    const { Wrapper } = wrapper();
    const { result } = renderHook(
      () => useDocumentTemplates(1, '/templates/delivery-note.html'),
      { wrapper: Wrapper },
    );

    await waitFor(() => expect(result.current.data).toHaveLength(1));

    expect(mockApi.get).toHaveBeenCalledWith(
      '/api/v1/document-templates?clientId=1&templatePath=%2Ftemplates%2Fdelivery-note.html',
    );
  });

  it('does not fetch when clientId or templatePath is missing', () => {
    const { Wrapper } = wrapper();
    renderHook(() => useDocumentTemplates(undefined, undefined), { wrapper: Wrapper });
    expect(mockApi.get).not.toHaveBeenCalled();
  });
});

describe('useUploadDocumentTemplate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.post.mockResolvedValue(TEMPLATE);
  });

  it('POSTs the create body and invalidates the templates list', async () => {
    const { Wrapper, qc } = wrapper();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useUploadDocumentTemplate(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync({
        clientId: 1,
        templatePath: '/templates/delivery-note.html',
        content: '<html>hi</html>',
      });
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/document-templates', {
      clientId: 1,
      templatePath: '/templates/delivery-note.html',
      content: '<html>hi</html>',
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['document-templates'] });
  });
});

describe('useActivateDocumentTemplate', () => {
  it('POSTs /{id}/activate and invalidates the templates list', async () => {
    mockApi.post.mockResolvedValue({ ...TEMPLATE, active: true });
    const { Wrapper, qc } = wrapper();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useActivateDocumentTemplate(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync(1);
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/document-templates/1/activate', {});
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['document-templates'] });
  });
});

describe('useDeleteDocumentTemplate', () => {
  it('DELETEs the template and invalidates the templates list', async () => {
    mockApi.delete.mockResolvedValue(undefined);
    const { Wrapper, qc } = wrapper();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useDeleteDocumentTemplate(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync(1);
    });

    expect(mockApi.delete).toHaveBeenCalledWith('/api/v1/document-templates/1');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['document-templates'] });
  });
});
