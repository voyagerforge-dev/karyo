// Requires @pact-foundation/pact >= 17.1.3. Below that floor the mock server commits a
// request's match result AFTER writing the response, so a concurrent run can read
// pre-commit state and fail a request the server already served. Do not downgrade.
import { PactV3, MatchersV3 } from '@pact-foundation/pact';
import { describe, it, expect } from 'vitest';

const { like, eachLike, integer, string, decimal } = MatchersV3;

const provider = new PactV3({
  consumer: 'karyo-dashboard',
  provider: 'layout-service',
  dir: './pacts',
});

describe('Location API Contract', () => {
  it('returns paginated locations', async () => {
    await provider
      .given('locations exist')
      .uponReceiving('a request for paginated locations')
      .withRequest({
        method: 'GET',
        path: '/api/v1/locations',
        query: { page: '0', size: '20' },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: {
          content: eachLike({
            id: integer(1),
            name: string('A-01-01'),
            allocation: decimal(0),
            lockType: integer(0),
            lockTypeName: string('None'),
            orderIndex: integer(0),
            xPos: integer(0),
            yPos: integer(0),
            zPos: integer(0),
            locationType: like({
              id: integer(1),
              name: string('Standard Rack'),
            }),
            area: like({
              id: integer(1),
              name: string('Receiving'),
              usages: eachLike(string('STORAGE')),
            }),
            created: string('2026-01-01T00:00:00Z'),
            modified: string('2026-01-01T00:00:00Z'),
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
        `${mockServer.url}/api/v1/locations?page=0&size=20`
      );
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data.content).toHaveLength(1);
      expect(data.content[0].name).toBeDefined();
      expect(data.page).toBeDefined();
    });
  });

  it('creates a location', async () => {
    await provider
      .given('area and location type exist')
      .uponReceiving('a request to create a location')
      .withRequest({
        method: 'POST',
        path: '/api/v1/locations',
        headers: { 'Content-Type': 'application/json' },
        body: {
          name: 'B-01-01',
          locationTypeId: 1,
          areaId: 1,
        },
      })
      .willRespondWith({
        status: 201,
        headers: { 'Content-Type': 'application/json' },
        body: {
          id: integer(2),
          name: string('B-01-01'),
          allocation: decimal(0),
          lockType: integer(0),
          lockTypeName: string('None'),
        },
      });

    await provider.executeTest(async (mockServer) => {
      const response = await fetch(`${mockServer.url}/api/v1/locations`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'B-01-01',
          locationTypeId: 1,
          areaId: 1,
        }),
      });
      expect(response.status).toBe(201);
      const data = await response.json();
      expect(data.name).toBe('B-01-01');
    });
  });
});
