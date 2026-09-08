import { useState } from 'react';
import { Lock } from 'lucide-react';
import { toast } from 'sonner';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
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
import { useLicense } from '@/features/license/use-license';
import { useClients } from '@/features/clients/use-clients';
import {
  useDocumentTemplates,
  useUploadDocumentTemplate,
  useActivateDocumentTemplate,
  useDeleteDocumentTemplate,
} from '@/features/documents/use-document-templates';
import { TEMPLATE_PATH_WHITELIST } from '@/types/document';

/**
 * Admin -> Document templates (D14, the `documents` paid engine's per-client template-override
 * administration). Gated behind `useLicense().isEntitled('documents')` -- mirrors the
 * monitors/forecasting/slotting/simulation pages EXACTLY: without the entitlement, render ONLY
 * the locked/upsell panel, with no client/template queries firing (the engine component below
 * is never mounted, so its hooks never run).
 */
function LockedTemplatesPanel() {
  return (
    <div
      data-testid="templates-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-signal/10">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Document templates is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Per-client overrides for every generated PDF/ZPL document, versioned with instant
        rollback — contact your account team to enable Document templates for this tenant.
      </p>
    </div>
  );
}

function TemplatesEngine() {
  const { data: clients = [] } = useClients();
  const [clientId, setClientId] = useState<number | undefined>(undefined);
  const [templatePath, setTemplatePath] = useState<string | undefined>(undefined);
  const [content, setContent] = useState('');
  const [pendingDelete, setPendingDelete] = useState<{ id: number; version: number } | null>(null);

  const { data: versions = [] } = useDocumentTemplates(clientId, templatePath);
  const upload = useUploadDocumentTemplate();
  const activate = useActivateDocumentTemplate();
  const deleteTemplate = useDeleteDocumentTemplate();

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const text = await file.text();
    setContent(text);
    e.target.value = '';
  }

  async function handleUpload() {
    if (clientId == null || !templatePath) {
      toast.error('Select a client and a template path first');
      return;
    }
    if (!content.trim()) {
      toast.error('Template content is required');
      return;
    }
    try {
      await upload.mutateAsync({ clientId, templatePath, content });
      toast.success('Template version uploaded');
      setContent('');
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  }

  async function handleActivate(id: number) {
    try {
      await activate.mutateAsync(id);
      toast.success('Version activated');
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  }

  async function handleConfirmDelete() {
    if (!pendingDelete) return;
    try {
      await deleteTemplate.mutateAsync(pendingDelete.id);
      toast.success('Version deleted');
    } catch {
      // api-client already surfaces a toast for the failed request.
    } finally {
      setPendingDelete(null);
    }
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Select
          value={clientId != null ? String(clientId) : ''}
          onValueChange={(v) => setClientId(Number(v))}
        >
          <SelectTrigger className="w-[220px]" data-testid="template-client-select">
            <SelectValue placeholder="Select client…" />
          </SelectTrigger>
          <SelectContent>
            {clients.map((c) => (
              <SelectItem key={c.id} value={String(c.id)}>
                {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={templatePath ?? ''} onValueChange={setTemplatePath}>
          <SelectTrigger className="w-[280px]" data-testid="template-path-select">
            <SelectValue placeholder="Select template path…" />
          </SelectTrigger>
          <SelectContent>
            {TEMPLATE_PATH_WHITELIST.map((path) => (
              <SelectItem key={path} value={path}>
                {path}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="mb-5 rounded-2xl border border-border bg-card p-5">
        <h2 className="mb-3 text-base font-semibold text-foreground">Upload new version</h2>
        <Textarea
          data-testid="template-upload-content"
          placeholder="Qute template source (HTML or ZPL)…"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          className="mb-3 min-h-40 font-mono text-[12px]"
        />
        <div className="flex items-center gap-3">
          <input
            type="file"
            data-testid="template-upload-file"
            accept=".html,.zpl,.txt"
            onChange={(e) => void handleFileChange(e)}
            className="text-[12px] text-muted-foreground"
          />
          <Button
            data-testid="template-upload-submit"
            disabled={upload.isPending}
            onClick={() => void handleUpload()}
          >
            Upload
          </Button>
        </div>
      </div>

      {clientId == null || !templatePath ? (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          Select a client and a template path to see its version history.
        </div>
      ) : versions.length === 0 ? (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          No overrides yet for this client + path — the bundled classpath template is in effect.
        </div>
      ) : (
        <div className="rounded-2xl border border-border bg-card">
          <Table data-testid="template-versions-table">
            <TableHeader>
              <TableRow>
                <TableHead>Version</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {versions.map((v) => (
                <TableRow key={v.id} data-testid={`template-version-row-${v.id}`}>
                  <TableCell className="numeric">v{v.templateVersion}</TableCell>
                  <TableCell>
                    {v.active ? <Badge>Active</Badge> : <Badge variant="secondary">Inactive</Badge>}
                  </TableCell>
                  <TableCell className="numeric text-[12px] text-muted-foreground">
                    {new Date(v.created).toLocaleString()}
                  </TableCell>
                  <TableCell className="text-right">
                    {!v.active && (
                      <Button
                        variant="ghost"
                        size="sm"
                        data-testid={`template-activate-${v.id}`}
                        onClick={() => void handleActivate(v.id)}
                      >
                        Activate
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid={`template-delete-${v.id}`}
                      onClick={() => setPendingDelete({ id: v.id, version: v.templateVersion })}
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
        <AlertDialogContent data-testid="template-delete-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Delete version?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will permanently delete version ${pendingDelete?.version} of this template override. This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              data-testid="template-confirm-delete-btn"
              onClick={() => void handleConfirmDelete()}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

export function AdminTemplatesPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('documents')) {
    body = <LockedTemplatesPanel />;
  } else {
    body = (
      <>
        <div className="mb-5">
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
            Document templates
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            Per-client overrides for the 10 generated document templates, versioned with instant
            rollback.
          </p>
        </div>
        <TemplatesEngine />
      </>
    );
  }

  return <div data-testid="admin-templates-page">{body}</div>;
}
