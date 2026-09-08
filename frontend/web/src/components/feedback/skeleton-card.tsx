import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * Skeleton loading card matching the summary card layout.
 * Shows animated placeholder shapes for icon, label, count, and action link.
 */
export function SkeletonCard() {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-4 w-4 rounded" />
      </CardHeader>
      <CardContent>
        <Skeleton className="h-8 w-16" />
      </CardContent>
      <CardFooter>
        <Skeleton className="h-4 w-14" />
      </CardFooter>
    </Card>
  );
}
