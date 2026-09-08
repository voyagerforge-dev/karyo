import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { PaginatedResponse } from '@/types/api';
import type { StoredDocumentResponse } from '@/types/document';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
const mockDownloadDocument = vi.fn();
vi.mock('@/lib/api-client', () => ({
  api: mockApi,
  downloadDocument: (...args: unknown[]) => mockDownloadDocument(...args),
}));

const toastSuccess = vi.fn();
vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { success: (...args: unknown[]) => toastSuccess(...args), error: vi.fn() }),
}));

const { AdminDocumentsPage } = await import('../admin-documents-page');

const DOCS: StoredDocumentResponse[] = [
  {
    id: 1,
    entityType: 'shipment',
    entityId: 50,
    documentType: 'bol',
    fileName: 'bol-50.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 2048,
    created: '2026-07-25T10:00:00Z',
  },
  {
    id: 2,
    entityType: 'unit-load',
    entityId: 9,
    documentType: 'label',
    fileName: 'ul-9.zpl',
    mediaType: 'text/plain; charset=utf-8',
    sizeBytes: 512,
    created: '2026-07-24T09:00:00Z',
  },
];

function pageOf(content: StoredDocumentResponse[]): PaginatedResponse<StoredDocumentResponse> {
  return { content, page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 } };
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(createElement(QueryClientProvider, { client: qc }, createElement(AdminDocumentsPage)));
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.get.mockResolvedValue(pageOf(DOCS));
  mockApi.delete.mockResolvedValue(undefined);
  mockDownloadDocument.mockResolvedValue(new Blob(['x'], { type: 'application/pdf' }));
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:test');
  globalThis.URL.revokeObjectURL = vi.fn();
});

describe('AdminDocumentsPage', () => {
  it('renders rows from the mocked list', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());
    expect(screen.getByText('bol-50.pdf')).toBeInTheDocument();
    expect(screen.getByText('ul-9.zpl')).toBeInTheDocument();
    expect(mockApi.get).toHaveBeenCalledWith(expect.stringContaining('/api/v1/documents?'));
  });

  it('shows the honest empty state when there are no archived documents', async () => {
    mockApi.get.mockResolvedValue(pageOf([]));
    renderPage();
    await waitFor(() =>
      expect(
        screen.getByText('No archived documents — use Archive on any document menu.'),
      ).toBeInTheDocument(),
    );
  });

  it('refetches with entityType in the query params when the entity-type filter changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());
    mockApi.get.mockClear();

    await user.click(screen.getByTestId('documents-filter-entity-type'));
    await user.click(await screen.findByText('shipment'));

    await waitFor(() =>
      expect(mockApi.get).toHaveBeenCalledWith(expect.stringContaining('entityType=shipment')),
    );
  });

  it('refetches with documentType in the query params when the document-type filter changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());
    mockApi.get.mockClear();

    await user.click(screen.getByTestId('documents-filter-document-type'));
    // 'packing-slip' doesn't collide with any documentType/fileName already rendered in the
    // table body from the DOCS fixture (unlike 'bol'/'label', which do).
    await user.click(await screen.findByText('packing-slip'));

    await waitFor(() =>
      expect(mockApi.get).toHaveBeenCalledWith(
        expect.stringContaining('documentType=packing-slip'),
      ),
    );
  });

  it('refetches with entityId in the query params when the free-text filter changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());
    mockApi.get.mockClear();

    await user.type(screen.getByTestId('documents-filter-entity-id'), '50');

    await waitFor(() =>
      expect(mockApi.get).toHaveBeenCalledWith(expect.stringContaining('entityId=50')),
    );
  });

  it('downloads via GET /{id}/content and saves with the stored fileName', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());

    await user.click(screen.getByTestId('admin-documents-download-1'));

    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith('/api/v1/documents/1/content'),
    );
  });

  it('delete requires confirmation, then DELETEs and invalidates the list', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('admin-documents-table')).toBeInTheDocument());

    await user.click(screen.getByTestId('admin-documents-delete-1'));
    expect(mockApi.delete).not.toHaveBeenCalled();

    expect(screen.getByTestId('admin-documents-delete-dialog')).toBeInTheDocument();
    mockApi.get.mockClear();
    await user.click(screen.getByTestId('admin-documents-confirm-delete-btn'));

    await waitFor(() => expect(mockApi.delete).toHaveBeenCalledWith('/api/v1/documents/1'));
    // Invalidation refetches the list.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalled());
  });
});
