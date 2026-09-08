import { keycloak, refreshSession } from '@/lib/keycloak';
import { toast } from 'sonner';
import type { ProblemDetail } from '@/types/api';

/**
 * API error wrapping an RFC 7807 ProblemDetail response.
 */
export class ApiError extends Error {
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail);
    this.name = 'ApiError';
    this.problem = problem;
  }
}

/**
 * Centralized fetch wrapper that:
 * 1. Refreshes JWT token before every request (no-op if still valid)
 * 2. Attaches Authorization: Bearer header
 * 3. Parses error responses as RFC 7807 ProblemDetail
 * 4. Shows toast notifications via Sonner
 * 5. Throws ApiError for callers to handle
 */
async function apiFetch<T>(
  url: string,
  options?: RequestInit,
  opts?: { silent?: boolean },
): Promise<T> {
  await refreshSession(30);

  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${keycloak.token}`,
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({
      type: 'https://karyo.dev/errors/unknown',
      title: 'Request Failed',
      status: response.status,
      detail: `Server returned ${response.status}`,
    }));

    // Show toast for API errors, unless the caller opted into silent handling
    // (e.g. a gate-discovery probe like /api/v1/demo/status, where a 404 is
    // an expected "feature is off" signal, not a user-facing failure).
    if (!opts?.silent) {
      if (problem.violations?.length) {
        toast.error(problem.title, {
          description: problem.violations
            .map((v) => `${v.field}: ${v.message}`)
            .join(', '),
          duration: Infinity, // Error toasts persist until dismissed
        });
      } else {
        toast.error(problem.title, {
          description: problem.detail,
          duration: Infinity,
        });
      }
    }

    throw new ApiError(problem);
  }

  // 204 No Content
  if (response.status === 204) return undefined as T;

  return response.json();
}

/**
 * Fetch a binary/text document (PDF/ZPL) with the JWT, returning a Blob.
 * Mirrors apiFetch's token refresh + RFC 7807 error toast; the `api` object stays JSON-only.
 */
export async function downloadDocument(url: string): Promise<Blob> {
  await refreshSession(30);
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${keycloak.token}` },
  });
  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({
      type: 'https://karyo.dev/errors/unknown',
      title: 'Download Failed',
      status: response.status,
      detail: `Server returned ${response.status}`,
    }));
    toast.error(problem.title, { description: problem.detail, duration: Infinity });
    throw new ApiError(problem);
  }
  return response.blob();
}

/**
 * API client with typed HTTP methods.
 * All requests include JWT Authorization header and RFC 7807 error handling.
 */
export const api = {
  get: <T>(url: string, opts?: { silent?: boolean }) => apiFetch<T>(url, undefined, opts),

  post: <T>(url: string, body: unknown) =>
    apiFetch<T>(url, { method: 'POST', body: JSON.stringify(body) }),

  patch: <T>(url: string, body: unknown) =>
    apiFetch<T>(url, { method: 'PATCH', body: JSON.stringify(body) }),

  put: <T>(url: string, body: unknown) =>
    apiFetch<T>(url, { method: 'PUT', body: JSON.stringify(body) }),

  delete: <T>(url: string) => apiFetch<T>(url, { method: 'DELETE' }),
};
