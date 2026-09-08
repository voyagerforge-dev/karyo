import { keycloak, refreshSession } from '@/lib/keycloak'
import { toast } from 'sonner'

export interface ProblemDetail { type: string; title: string; status: number; detail: string; instance?: string }

export class ApiError extends Error {
  readonly problem: ProblemDetail
  constructor(problem: ProblemDetail) { super(problem.detail); this.name = 'ApiError'; this.problem = problem }
}

async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  await refreshSession(30)
  const response = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${keycloak.token}`, ...options?.headers },
  })
  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({
      type: 'about:blank', title: 'Request failed', status: response.status, detail: `Server returned ${response.status}`,
    }))
    throw new ApiError(problem)
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

/** True when a failure is a connectivity problem (enqueue) rather than a real HTTP error (surface). */
export function isOfflineError(e: unknown): boolean {
  if (!navigator.onLine) return true
  return e instanceof TypeError // fetch() rejects with TypeError on network failure
}

export const api = {
  get: <T>(url: string) => apiFetch<T>(url),
  post: <T>(url: string, body?: unknown) => apiFetch<T>(url, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(url: string) => apiFetch<T>(url, { method: 'DELETE' }),
}
export { toast }
