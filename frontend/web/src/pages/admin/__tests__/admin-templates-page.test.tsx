import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { ClientResponse } from '@/types/client';
import type { DocumentTemplateResponse } from '@/types/document';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const mockApi = { get: vi.fn(), post: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const mockIsEntitled = vi.fn();
const mockUseLicense = vi.fn(() => ({ isLoading: false, isEntitled: mockIsEntitled }));
vi.mock('@/features/license/use-license', () => ({ useLicense: () => mockUseLicense() }));

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { success: (...args: unknown[]) => toastSuccess(...args), error: (...args: unknown[]) => toastError(...args) }),
}));

const { AdminTemplatesPage } = await import('../admin-templates-page');

const CLIENTS: ClientResponse[] = [
  { id: 1, name: 'Acme', number: 'CL-1', code: '', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: false },
  { id: 2, name: 'Globex', number: 'CL-2', code: '', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: false },
];

const VERSIONS: DocumentTemplateResponse[] = [
  {
    id: 10,
    clientId: 1,
    templatePath: '/templates/delivery-note.html',
    templateVersion: 2,
    active: true,
    content: '<html>v2</html>',
    created: '2026-07-25T10:00:00Z',
    modified: '2026-07-25T10:00:00Z',
  },
  {
    id: 9,
    clientId: 1,
    templatePath: '/templates/delivery-note.html',
    templateVersion: 1,
    active: false,
    content: '<html>v1</html>',
    created: '2026-07-24T10:00:00Z',
    modified: '2026-07-24T10:00:00Z',
  },
];

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(createElement(QueryClientProvider, { client: qc }, createElement(AdminTemplatesPage)));
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsEntitled.mockReturnValue(true);
  mockApi.get.mockImplementation((url: string) => {
    if (url.startsWith('/api/v1/clients')) return Promise.resolve(CLIENTS);
    if (url.startsWith('/api/v1/document-templates')) return Promise.resolve(VERSIONS);
    return Promise.resolve([]);
  });
});

async function selectClientAndPath(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('template-client-select'));
  await user.click(await screen.findByText('Acme'));

  await user.click(screen.getByTestId('template-path-select'));
  await user.click(await screen.findByText('/templates/delivery-note.html'));
}

describe('AdminTemplatesPage', () => {
  it('renders only the locked panel when the documents entitlement is absent, firing no template queries', async () => {
    mockIsEntitled.mockReturnValue(false);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('templates-locked')).toBeInTheDocument());
    expect(screen.queryByTestId('template-client-select')).not.toBeInTheDocument();
    expect(mockApi.get).not.toHaveBeenCalled();
  });

  it('renders the licensed engine with client + template-path selectors', async () => {
    renderPage();
    expect(screen.getByTestId('admin-templates-page')).toBeInTheDocument();
    expect(screen.queryByTestId('templates-locked')).not.toBeInTheDocument();
    expect(screen.getByTestId('template-client-select')).toBeInTheDocument();
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/clients');
  });

  it('the template-path selector offers all 10 whitelisted paths', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByTestId('template-path-select'));

    for (const path of [
      '/templates/packing-slip.html',
      '/templates/bol.html',
      '/templates/label.zpl',
      '/templates/packet-list.html',
      '/templates/packet-content-list.html',
      '/templates/pick-ticket.html',
      '/templates/delivery-note.html',
      '/templates/ul-content-list.html',
      '/templates/ul-label.zpl',
      '/templates/location-label.zpl',
    ]) {
      expect(await screen.findByText(path)).toBeInTheDocument();
    }
  });

  it('fetches versions once both a client and a template path are selected', async () => {
    const user = userEvent.setup();
    renderPage();
    mockApi.get.mockClear();

    await selectClientAndPath(user);

    await waitFor(() =>
      expect(mockApi.get).toHaveBeenCalledWith(
        expect.stringContaining('/api/v1/document-templates?'),
      ),
    );
    await waitFor(() => expect(screen.getByTestId('template-version-row-10')).toBeInTheDocument());
    expect(screen.getByTestId('template-version-row-9')).toBeInTheDocument();
  });

  it('uploads a new version via the textarea content', async () => {
    const user = userEvent.setup();
    mockApi.post.mockResolvedValue(VERSIONS[0]);
    renderPage();

    await selectClientAndPath(user);
    await waitFor(() => expect(screen.getByTestId('template-version-row-10')).toBeInTheDocument());

    await user.type(screen.getByTestId('template-upload-content'), '<html>new</html>');
    await user.click(screen.getByTestId('template-upload-submit'));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith('/api/v1/document-templates', {
        clientId: 1,
        templatePath: '/templates/delivery-note.html',
        content: '<html>new</html>',
      }),
    );
  });

  it('activates an inactive version', async () => {
    const user = userEvent.setup();
    mockApi.post.mockResolvedValue({ ...VERSIONS[1], active: true });
    renderPage();

    await selectClientAndPath(user);
    await waitFor(() => expect(screen.getByTestId('template-version-row-9')).toBeInTheDocument());

    await user.click(screen.getByTestId('template-activate-9'));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith('/api/v1/document-templates/9/activate', {}),
    );
    // The already-active version (10) has no activate action offered.
    expect(screen.queryByTestId('template-activate-10')).not.toBeInTheDocument();
  });

  it('deletes a version after confirmation', async () => {
    const user = userEvent.setup();
    mockApi.delete.mockResolvedValue(undefined);
    renderPage();

    await selectClientAndPath(user);
    await waitFor(() => expect(screen.getByTestId('template-version-row-9')).toBeInTheDocument());

    await user.click(screen.getByTestId('template-delete-9'));
    expect(mockApi.delete).not.toHaveBeenCalled();
    expect(screen.getByTestId('template-delete-dialog')).toBeInTheDocument();

    await user.click(screen.getByTestId('template-confirm-delete-btn'));

    await waitFor(() => expect(mockApi.delete).toHaveBeenCalledWith('/api/v1/document-templates/9'));
  });
});
