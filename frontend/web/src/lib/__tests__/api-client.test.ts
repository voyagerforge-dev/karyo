import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock keycloak module before importing api-client
vi.mock('@/lib/keycloak', () => ({
  keycloak: {
    token: 'mock-jwt-token',
    updateToken: vi.fn().mockResolvedValue(true),
  },
  refreshSession: vi.fn().mockResolvedValue(true),
  startLogin: vi.fn(),
}));

// Mock sonner toast to prevent side effects
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}));

// Import after mocking
const { api, ApiError } = await import('@/lib/api-client');
const { refreshSession, startLogin } = await import('@/lib/keycloak');
const { toast } = await import('sonner');

describe('api-client', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it('successful GET returns parsed JSON', async () => {
    const mockData = { content: [{ id: 1 }], page: { totalElements: 42 } };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(mockData), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const result = await api.get('/api/v1/products');
    expect(result).toEqual(mockData);
    expect(refreshSession).toHaveBeenCalledWith(30);
  });

  it('does not start login when token refresh fails for a transport error', async () => {
    vi.mocked(refreshSession).mockRejectedValueOnce(new Error('identity service unavailable'));
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await expect(api.get('/api/v1/products')).rejects.toThrow('identity service unavailable');
    expect(startLogin).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('400 response throws ApiError with ProblemDetail', async () => {
    const problem = {
      type: 'https://karyo.dev/errors/validation',
      title: 'Validation Error',
      status: 400,
      detail: 'Name is required',
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(problem), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );

    try {
      await api.get('/api/v1/products');
      expect.unreachable('Should have thrown');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as InstanceType<typeof ApiError>;
      expect(apiError.problem.status).toBe(400);
      expect(apiError.problem.title).toBe('Validation Error');
      expect(apiError.problem.detail).toBe('Name is required');
    }
  });

  it('non-JSON error response creates fallback ProblemDetail', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('Internal Server Error', {
        status: 500,
        headers: { 'Content-Type': 'text/plain' },
      }),
    );

    await expect(api.get('/api/v1/products')).rejects.toThrow(ApiError);
    try {
      await api.get('/api/v1/products');
    } catch (error) {
      const apiError = error as InstanceType<typeof ApiError>;
      expect(apiError.problem.status).toBe(500);
      expect(apiError.problem.title).toBe('Request Failed');
      expect(apiError.problem.detail).toBe('Server returned 500');
    }
  });

  it('204 response returns undefined', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(null, { status: 204 }),
    );

    const result = await api.delete('/api/v1/products/1');
    expect(result).toBeUndefined();
  });

  it('attaches Authorization header with JWT token', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({}), { status: 200 }),
    );

    await api.get('/api/v1/products');

    const [, options] = fetchSpy.mock.calls[0];
    expect((options?.headers as Record<string, string>)['Authorization']).toBe(
      'Bearer mock-jwt-token',
    );
  });

  // Task 11 (defect-burndown): gate-discovery probes like GET /api/v1/demo/status
  // opt out of the global error toast via `{ silent: true }` -- a 404 there is
  // an expected "feature is off" signal, not a user-facing failure. The
  // request still throws ApiError (react-query's error state still reflects
  // it); only the toast is suppressed.
  it('GET with { silent: true } suppresses the error toast but still throws ApiError', async () => {
    const problem = {
      type: 'https://karyo.dev/errors/unknown',
      title: 'Not Found',
      status: 404,
      detail: 'Not found',
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(problem), {
        status: 404,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );

    await expect(
      api.get('/api/v1/demo/status', { silent: true }),
    ).rejects.toThrow(ApiError);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('GET without { silent: true } still shows the error toast on failure', async () => {
    const problem = {
      type: 'https://karyo.dev/errors/unknown',
      title: 'Not Found',
      status: 404,
      detail: 'Not found',
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(problem), {
        status: 404,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );

    await expect(api.get('/api/v1/products')).rejects.toThrow(ApiError);
    expect(toast.error).toHaveBeenCalled();
  });
});
