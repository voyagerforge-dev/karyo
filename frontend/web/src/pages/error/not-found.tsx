import { useNavigate } from 'react-router';
import { ErrorPage } from '@/components/feedback/error-page';

/**
 * 404 Not Found page.
 */
export function NotFound() {
  const navigate = useNavigate();

  return (
    <ErrorPage
      title="Page Not Found"
      description="The page you're looking for doesn't exist."
      onRetry={() => navigate('/')}
    />
  );
}
