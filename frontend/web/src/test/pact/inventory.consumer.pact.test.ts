// Requires @pact-foundation/pact >= 17.1.3. Below that floor the mock server commits a
// request's match result AFTER writing the response, so a concurrent run can read
// pre-commit state and fail a request the server already served. Do not downgrade.
import { PactV3, MatchersV3 } from '@pact-foundation/pact';
import { describe, it, expect } from 'vitest';

const { eachLike, integer, string, decimal } = MatchersV3;

const provider = new PactV3({
  consumer: 'karyo-dashboard',
  provider: 'inventory-service',
  dir: './pacts',
});

describe('Inventory API Contract', () => {
  it('returns paginated stock units', async () => {
    await provider
      .given('stock units exist')
      .uponReceiving('a request for paginated stock units')
      .withRequest({
        method: 'GET',
        path: '/api/v1/stock-units',
        query: { page: '0', size: '20' },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: {
          content: eachLike({
            id: integer(1),
            itemDataId: integer(100),
            itemDataNumber: string('SKU-001'),
            amount: decimal(50.0),
            reservedAmount: decimal(0.0),
            availableAmount: decimal(50.0),
            state: integer(300),
            stateName: string('ON_STOCK'),
            unitLoadId: integer(10),
            unitLoadLabel: string('UL-001'),
            locationId: integer(5),
            locationName: string('A-01-01'),
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
        `${mockServer.url}/api/v1/stock-units?page=0&size=20`
      );
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data.content).toHaveLength(1);
      expect(data.page).toBeDefined();
      expect(data.page.number).toBe(0);
    });
  });

  // GET /api/v1/unit-loads has no default (unscoped) listing -- it's always location-scoped
  // (locationId is a required query param; the real frontend never calls it any other way, e.g.
  // use-inventory.ts / manual-move-form.tsx) and returns a bare array, NOT the paginated
  // {content, page} envelope. The prior version of this interaction tested a shape the endpoint
  // never actually had -- corrected here (2026-07-31, task 12 pact-honesty pass) against the live
  // UnitLoadResource.list() signature and real frontend call sites.
  it('returns unit loads for a location', async () => {
    await provider
      .given('unit loads exist')
      .uponReceiving('a request for unit loads at a location')
      .withRequest({
        method: 'GET',
        path: '/api/v1/unit-loads',
        query: { locationId: '1' },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: eachLike({
          id: integer(1),
          labelId: string('UL-001'),
          unitLoadTypeId: integer(1),
          unitLoadTypeName: string('Euro Pallet'),
          storageLocationId: integer(1),
          storageLocationName: string('A-01-01'),
          state: integer(0),
          lockType: integer(0),
          lockTypeName: string('UNDEFINED'),
          created: string('2026-01-01T00:00:00Z'),
        }),
      });

    await provider.executeTest(async (mockServer) => {
      const response = await fetch(
        `${mockServer.url}/api/v1/unit-loads?locationId=1`
      );
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data).toHaveLength(1);
      expect(data[0].labelId).toBeDefined();
    });
  });
});
