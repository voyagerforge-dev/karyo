import { useState } from 'react';
import { toast } from 'sonner';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { useDocuments, useDeleteDocument, downloadArchivedDocument } from '@/features/documents/use-documents';
import { DOCUMENT_ENTITY_TYPES, DOCUMENT_TYPES } from '@/types/document';

const PAGE_SIZE = 100;
const ALL = 'all';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Admin -> Documents (D13 archive browser). Lists every stored PDF/ZPL -- archived either
 * via `?store=true` on the 10 live document endpoints, or the "Archive" action on any
 * document menu. A plain filterable table, not a master-detail layout: archived documents
 * are leaf records (view/delete), there's no sub-detail to drill into (mirrors
 * AdminAuditPage, not the master-detail AdminClientsPage).
 */
export function AdminDocumentsPage() {
  const [entityType, setEntityType] = useState<string>(ALL);
  const [documentType, setDocumentType] = useState<string>(ALL);
  const [entityId, setEntityId] = useState('');
  const [pendingDelete, setPendingDelete] = useState<{ id: number; fileName: string } | null>(
    null,
  );

  const trimmedEntityId = entityId.trim();
  const parsedEntityId =
    trimmedEntityId !== '' && !Number.isNaN(Number(trimmedEntityId))
      ? Number(trimmedEntityId)
      : undefined;

  const { data, isLoading, isError } = useDocuments({
    entityType: entityType === ALL ? undefined : entityType,
    documentType: documentType === ALL ? undefined : documentType,
    entityId: parsedEntityId,
    page: 0,
    size: PAGE_SIZE,
  });
  const deleteDocument = useDeleteDocument();
  const rows = data?.content ?? [];

  async function handleConfirmDelete() {
    if (!pendingDelete) return;
    try {
      await deleteDocument.mutateAsync(pendingDelete.id);
      toast.success('Document deleted');
    } catch {
      // api-client already surfaces a toast for the failed request.
    } finally {
      setPendingDelete(null);
    }
  }

  return (
    <div data-testid="admin-documents-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Documents
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Archived PDFs and ZPL labels — opted in via Archive on any document menu.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Select value={entityType} onValueChange={setEntityType}>
          <SelectTrigger className="w-[180px]" data-testid="documents-filter-entity-type">
            <SelectValue placeholder="Entity type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All entity types</SelectItem>
            {DOCUMENT_ENTITY_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {t}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={documentType} onValueChange={setDocumentType}>
          <SelectTrigger className="w-[180px]" data-testid="documents-filter-document-type">
            <SelectValue placeholder="Document type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All document types</SelectItem>
            {DOCUMENT_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {t}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Input
          placeholder="Entity id…"
          value={entityId}
          onChange={(e) => setEntityId(e.target.value)}
          className="w-[140px]"
          data-testid="documents-filter-entity-id"
        />
      </div>

      {isLoading && (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          Loading documents…
        </div>
      )}

      {!isLoading && isError && (
        <div
          data-testid="admin-documents-error"
          className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-destructive"
        >
          Couldn&apos;t load the documents archive.
        </div>
      )}

      {!isLoading && !isError && rows.length === 0 && (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          No archived documents — use Archive on any document menu.
        </div>
      )}

      {!isLoading && !isError && rows.length > 0 && (
        <div className="rounded-2xl border border-border bg-card">
          <Table data-testid="admin-documents-table">
            <TableHeader>
              <TableRow>
                <TableHead>Type</TableHead>
                <TableHead>Entity</TableHead>
                <TableHead>File</TableHead>
                <TableHead className="text-right">Size</TableHead>
                <TableHead>Created</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((doc) => (
                <TableRow key={doc.id} data-testid={`admin-documents-row-${doc.id}`}>
                  <TableCell>{doc.documentType}</TableCell>
                  <TableCell className="numeric text-[12px] text-muted-foreground">
                    {doc.entityType} #{doc.entityId}
                  </TableCell>
                  <TableCell>{doc.fileName}</TableCell>
                  <TableCell className="numeric text-right">{formatSize(doc.sizeBytes)}</TableCell>
                  <TableCell className="numeric text-[12px] text-muted-foreground">
                    {new Date(doc.created).toLocaleString()}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid={`admin-documents-download-${doc.id}`}
                      onClick={() => downloadArchivedDocument(doc.id, doc.fileName)}
                    >
                      Download
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid={`admin-documents-delete-${doc.id}`}
                      onClick={() => setPendingDelete({ id: doc.id, fileName: doc.fileName })}
                    >
                      Delete
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <AlertDialog
        open={pendingDelete != null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
      >
        <AlertDialogContent data-testid="admin-documents-delete-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Delete document?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will permanently delete "${pendingDelete?.fileName}" from the archive. This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              data-testid="admin-documents-confirm-delete-btn"
              onClick={handleConfirmDelete}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
