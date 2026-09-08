import { useState } from 'react';
import { AlertCircle, ChevronDown, ChevronUp } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface ErrorPageProps {
  title: string;
  description: string;
  /** Optional error details (shown behind a toggle) */
  details?: string;
  /** Optional retry callback */
  onRetry?: () => void;
}

/**
 * Full-page error display that replaces the content area.
 * Sidebar remains visible for navigation.
 */
export function ErrorPage({
  title,
  description,
  details,
  onRetry,
}: ErrorPageProps) {
  const [showDetails, setShowDetails] = useState(false);

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10">
        <AlertCircle className="h-8 w-8 text-destructive" />
      </div>

      <div className="space-y-2">
        <h1 className="text-2xl font-semibold">{title}</h1>
        <p className="max-w-md text-muted-foreground">{description}</p>
      </div>

      <div className="flex gap-3">
        {onRetry && (
          <Button onClick={onRetry}>Try Again</Button>
        )}
      </div>

      {details && (
        <div className="w-full max-w-lg">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowDetails(!showDetails)}
            className="text-xs text-muted-foreground"
          >
            {showDetails ? (
              <>
                <ChevronUp className="mr-1 h-3 w-3" />
                Hide details
              </>
            ) : (
              <>
                <ChevronDown className="mr-1 h-3 w-3" />
                Show details
              </>
            )}
          </Button>
          {showDetails && (
            <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-muted p-4 text-left font-mono text-xs text-muted-foreground">
              {details}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}
