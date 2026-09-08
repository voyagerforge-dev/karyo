import { toast } from 'sonner';
import { downloadDocument } from '@/lib/api-client';

/**
 * Shared document-download helpers (moved here from `pages/shipments/document-actions.ts`
 * during D12 -- CSV export needed a home usable by both the orders and inventory pages, not
 * just shipments, so the whole file moved up to `lib/` rather than duplicating the pattern).
 */

/** Open a PDF document in a new browser tab (view/print). */
export async function viewPdf(url: string): Promise<void> {
  const blob = await downloadDocument(url);
  const objectUrl = URL.createObjectURL(blob);
  window.open(objectUrl, '_blank');
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
}

/** Download a ZPL label as a .zpl file (for a Zebra printer). */
export async function saveZpl(url: string, filename: string): Promise<void> {
  const blob = await downloadDocument(url);
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(objectUrl);
}

/**
 * Download a CSV export (D12 -- Excel-compatible: UTF-8 BOM + CRLF rows, embedded server-side
 * by `CsvWriter`, nothing special needed on this end) as a file.
 */
export async function saveCsv(url: string, filename: string): Promise<void> {
  const blob = await downloadDocument(url);
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(objectUrl);
}

/**
 * Archive a generated document into the persistent store (D13) by replaying the same
 * request with `?store=true` appended -- the server writes the archive row as a side
 * effect of regenerating the document; the bytes returned here are discarded (the point
 * is the write, not a second download). `downloadDocument` already surfaces an RFC 7807
 * error toast on failure, so failure here is a silent catch (mirrors the
 * handleCarrier/handleUnlock pattern in inventory-detail.tsx).
 */
export async function archiveDocument(url: string): Promise<void> {
  const storeUrl = `${url}${url.includes('?') ? '&' : '?'}store=true`;
  try {
    await downloadDocument(storeUrl);
    toast.success('Document archived');
  } catch {
    // downloadDocument already surfaced an error toast.
  }
}
