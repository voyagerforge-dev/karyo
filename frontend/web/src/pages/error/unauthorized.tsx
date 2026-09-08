import { ErrorPage } from '@/components/feedback/error-page';

/**
 * 403 Unauthorized/Forbidden page.
 */
export function Unauthorized() {
  return (
    <ErrorPage
      title="Access Denied"
      description="You don't have permission to view this page."
    />
  );
}
