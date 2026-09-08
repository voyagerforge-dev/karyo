import { describe, expect, it } from 'vitest';
import { getAsnStatus, matchesAsnFilter } from '../asn-status';

describe('getAsnStatus', () => {
  it('maps ASN states to status info', () => {
    expect(getAsnStatus({ state: 50 })).toEqual({ label: 'Created', tone: 'grey' });
    expect(getAsnStatus({ state: 100 })).toEqual({ label: 'Released', tone: 'amber' });
    expect(getAsnStatus({ state: 500 })).toEqual({ label: 'Receiving', tone: 'lime' });
    expect(getAsnStatus({ state: 700 })).toEqual({ label: 'Finished', tone: 'blue' });
    expect(getAsnStatus({ state: 800 })).toEqual({ label: 'Canceled', tone: 'red' });
  });

  it('filters: created/receiving/done partition', () => {
    const created = { state: 50 };
    const released = { state: 100 };
    const started = { state: 500 };
    const finished = { state: 700 };
    const canceled = { state: 800 };

    expect(matchesAsnFilter(created, 'created')).toBe(true);
    expect(matchesAsnFilter(released, 'created')).toBe(false);

    expect(matchesAsnFilter(released, 'receiving')).toBe(true);
    expect(matchesAsnFilter(started, 'receiving')).toBe(true);
    expect(matchesAsnFilter(created, 'receiving')).toBe(false);

    expect(matchesAsnFilter(finished, 'done')).toBe(true);
    expect(matchesAsnFilter(canceled, 'done')).toBe(true);
    expect(matchesAsnFilter(created, 'done')).toBe(false);

    expect(matchesAsnFilter(created, 'all')).toBe(true);
    expect(matchesAsnFilter(finished, 'all')).toBe(true);
  });
});
