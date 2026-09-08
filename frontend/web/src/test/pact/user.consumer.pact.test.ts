// Requires @pact-foundation/pact >= 17.1.3. Below that floor the mock server commits a
// request's match result AFTER writing the response, so a concurrent run can read
// pre-commit state and fail a request the server already served. Do not downgrade.
import { PactV3, MatchersV3 } from '@pact-foundation/pact';
import { describe, it, expect } from 'vitest';

const { like, eachLike, integer, string } = MatchersV3;

const provider = new PactV3({
  consumer: 'karyo-dashboard',
  provider: 'auth-service',
  dir: './pacts',
});

describe('User API Contract', () => {
  it('returns paginated users', async () => {
    await provider
      .given('users exist')
      .uponReceiving('a request for paginated users')
      .withRequest({
        method: 'GET',
        path: '/api/v1/users',
        query: { page: '0', size: '20' },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: {
          content: eachLike({
            id: string('550e8400-e29b-41d4-a716-446655440000'),
            username: string('admin'),
            email: like('admin@karyo.dev'),
            firstName: like('Admin'),
            lastName: like('User'),
            enabled: like(true),
            roles: eachLike(string('ADMIN')),
          }),
          page: {
            number: integer(0),
            size: integer(20),
            totalElements: integer(1),
            totalPages: integer(1),
          },
        },
      });

    await provider.executeTest(async (mockServer) => {
      const response = await fetch(
        `${mockServer.url}/api/v1/users?page=0&size=20`
      );
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data.content).toHaveLength(1);
      expect(data.content[0].username).toBeDefined();
      expect(data.content[0].roles).toBeDefined();
      expect(data.page).toBeDefined();
    });
  });
});
