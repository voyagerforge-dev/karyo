import { describe, it, expect, vi, beforeEach } from 'vitest';
import { viewPdf, saveZpl, saveCsv, archiveDocument } from '../document-actions';
import { downloadDocument } from '@/lib/api-client';

vi.mock('@/lib/api-client', () => ({ downloadDocument: vi.fn() }));

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock('sonner', () => ({
  toast: { success: (...args: unknown[]) => toastSuccess(...args), error: (...args: unknown[]) => toastError(...args) },
}));

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(downloadDocument).mockResolvedValue(new Blob(['x'], { type: 'application/pdf' }));
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:test');
  globalThis.URL.revokeObjectURL = vi.fn();
});

describe('document-actions', () => {
  it('viewPdf fetches the url and opens a tab', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    await viewPdf('/api/v1/shipments/1/bol.pdf');
    expect(downloadDocument).toHaveBeenCalledWith('/api/v1/shipments/1/bol.pdf');
    expect(open).toHaveBeenCalledWith('blob:test', '_blank');
  });

  it('saveZpl fetches the url and clicks a download anchor', async () => {
    const click = vi.fn();
    const a = { href: '', download: '', click } as unknown as HTMLAnchorElement;
    vi.spyOn(document, 'createElement').mockReturnValue(a);
    await saveZpl('/api/v1/shipping-units/9/label.zpl', 'SHP-1-SU1.zpl');
    expect(downloadDocument).toHaveBeenCalledWith('/api/v1/shipping-units/9/label.zpl');
    expect(a.download).toBe('SHP-1-SU1.zpl');
    expect(click).toHaveBeenCalled();
  });

  it('saveCsv fetches the url and clicks a download anchor', async () => {
    vi.mocked(downloadDocument).mockResolvedValue(new Blob(['﻿a,b\r\n1,2\r\n'], { type: 'text/csv' }));
    const click = vi.fn();
    const a = { href: '', download: '', click } as unknown as HTMLAnchorElement;
    vi.spyOn(document, 'createElement').mockReturnValue(a);
    await saveCsv('/api/v1/delivery-orders/export.csv', 'delivery-orders.csv');
    expect(downloadDocument).toHaveBeenCalledWith('/api/v1/delivery-orders/export.csv');
    expect(a.download).toBe('delivery-orders.csv');
    expect(click).toHaveBeenCalled();
  });

  describe('archiveDocument', () => {
    it('appends ?store=true when the url has no existing query string', async () => {
      await archiveDocument('/api/v1/shipments/1/bol.pdf');
      expect(downloadDocument).toHaveBeenCalledWith('/api/v1/shipments/1/bol.pdf?store=true');
      expect(toastSuccess).toHaveBeenCalledWith('Document archived');
    });

    it('appends &store=true when the url already has a query string', async () => {
      await archiveDocument('/api/v1/shipments/1/bol.pdf?foo=bar');
      expect(downloadDocument).toHaveBeenCalledWith('/api/v1/shipments/1/bol.pdf?foo=bar&store=true');
      expect(toastSuccess).toHaveBeenCalledWith('Document archived');
    });

    it('swallows a failed archive without re-throwing (downloadDocument already toasted)', async () => {
      vi.mocked(downloadDocument).mockRejectedValue(new Error('boom'));
      await expect(archiveDocument('/api/v1/shipments/1/bol.pdf')).resolves.toBeUndefined();
      expect(toastSuccess).not.toHaveBeenCalled();
    });
  });
});
