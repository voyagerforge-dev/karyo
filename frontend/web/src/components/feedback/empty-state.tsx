import type { LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description: string;
  /** Optional action button label */
  actionLabel?: string;
  /** Optional action callback */
  onAction?: () => void;
}

/**
 * Centered empty state card with icon, text, and optional action button.
 * Used when a list/page has no data yet.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  actionLabel,
  onAction,
}: EmptyStateProps) {
  return (
    <div className="flex min-h-[40vh] flex-col items-center justify-center gap-4 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
        <Icon className="h-8 w-8 text-muted-foreground" />
      </div>

      <div className="space-y-2">
        <h2 className="text-xl font-semibold">{title}</h2>
        <p className="max-w-sm text-muted-foreground">{description}</p>
      </div>

      {actionLabel && onAction && (
        <Button onClick={onAction}>{actionLabel}</Button>
      )}
    </div>
  );
}
