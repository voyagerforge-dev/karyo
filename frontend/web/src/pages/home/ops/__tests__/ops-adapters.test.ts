import { describe, it, expect } from 'vitest';
import {
  kpisToCells,
  kpiChartToThroughput,
  occupancyToZoneField,
  alertsToExceptions,
} from '../ops-adapters';
import type { KpiDashboardResponse, OccupancyResponse } from '@/types/insights';
import type { AlertDto } from '@/pages/monitors/monitors-api';

const kpiRes: KpiDashboardResponse = {
  range: '7D',
  rangeLabel: 'Last 7 days',
  tiles: [
    {
      key: 'accuracy',
      label: 'Pick accuracy',
      value: '99.2%',
      delta: '+0.3',
      tone: 'up',
      series: [
        { day: 'Mon', value: 99 },
        { day: 'Tue', value: 99.2 },
      ],
    },
    {
      key: 'throughput',
      label: 'Units / hr',
      value: '142',
      delta: '+5',
      tone: 'up',
      series: [
        { day: 'Mon', value: 130 },
        { day: 'Tue', value: 142 },
      ],
    },
    { key: 'cycleTime', label: 'Dock-to-stock', value: '38m', delta: '-2m', tone: 'up', series: [] },
    { key: 'utilization', label: 'Utilization', value: '76%', delta: null, tone: 'up', series: [] },
  ],
  chart: {
    outbound: [
      { day: 'Mon', value: 120 },
      { day: 'Tue', value: 180 },
      { day: 'Wed', value: 90 },
    ],
    received: [
      { day: 'Mon', value: 60 },
      { day: 'Tue', value: 40 },
      { day: 'Wed', value: 30 },
    ],
  },
};

describe('kpisToCells', () => {
  it('maps the 4 real KPI keys into KpiCell shape', () => {
    const cells = kpisToCells(kpiRes, null);
    expect(cells).toHaveLength(4);
    expect(cells.map((c) => c.label)).toEqual([
      'Pick accuracy',
      'Units / hr',
      'Dock-to-stock',
      'Utilization',
    ]);
    expect(cells[0].value).toBe('99.2%');
  });

  it('derives the delta arrow from the sign of the delta string', () => {
    const cells = kpisToCells(kpiRes, null);
    // accuracy: '+0.3' -> up arrow, text without sign
    expect(cells[0].deltaArrow).toBe('▲');
    expect(cells[0].deltaText).toBe('0.3');
    // cycleTime: '-2m' -> down arrow, text without sign
    expect(cells[2].deltaArrow).toBe('▼');
    expect(cells[2].deltaText).toBe('2m');
  });

  it('renders a blank (non-crashing) delta when delta is null', () => {
    const cells = kpisToCells(kpiRes, null);
    expect(cells[3].deltaText).toBe('');
  });

  it('builds a sparkline points string from a non-empty series, and an empty string for an empty series', () => {
    const cells = kpisToCells(kpiRes, null);
    expect(cells[0].points.length).toBeGreaterThan(0);
    expect(cells[2].points).toBe('');
  });

  it('appends an Open exceptions cell when a firing count is supplied', () => {
    const cells = kpisToCells(kpiRes, 6);
    expect(cells).toHaveLength(5);
    expect(cells[4].label).toBe('Open exceptions');
    expect(cells[4].value).toBe('6');
    expect(cells[4].tone).toBe('danger');
  });

  it('omits the exceptions cell when count is null (monitors not entitled)', () => {
    expect(kpisToCells(kpiRes, null)).toHaveLength(4);
  });

  it('does not paint the exceptions cell danger when the count is zero', () => {
    const cells = kpisToCells(kpiRes, 0);
    expect(cells[4].value).toBe('0');
    expect(cells[4].tone).toBe('up');
  });
});

describe('kpiChartToThroughput', () => {
  it('builds daily bars from chart.outbound with a normalized peak', () => {
    const t = kpiChartToThroughput(kpiRes);
    expect(t.bars).toHaveLength(3);
    const peak = t.bars.find((b) => b.isPeak);
    expect(peak?.pct).toBe(100); // Tue=180 is the max -> 100%
    expect(t.bars[0].pct).toBe(Math.round((120 / 180) * 100));
    expect(t.bars.map((b) => b.label)).toEqual(['Mon', 'Tue', 'Wed']);
  });

  it('handles an empty outbound series without crashing', () => {
    const empty: KpiDashboardResponse = { ...kpiRes, chart: { outbound: [], received: [] } };
    const t = kpiChartToThroughput(empty);
    expect(t.bars).toHaveLength(0);
  });
});

describe('occupancyToZoneField', () => {
  it('renders one cell per real location, colored by state, with real facility pct', () => {
    const occ: OccupancyResponse = {
      zones: [
        {
          zoneId: 1,
          zoneName: 'A',
          occupied: 1,
          total: 2,
          pct: 50,
          locations: [
            { id: 1, name: 'A-01', state: 'occupied' },
            { id: 2, name: 'A-02', state: 'empty' },
          ],
        },
      ],
      unzoned: null,
      totals: { occupied: 1, total: 2, pct: 50 },
    };
    const z = occupancyToZoneField(occ);
    expect(z.cells).toHaveLength(2);
    expect(z.sub).toContain('50');
  });

  it('includes unzoned locations in the flattened cell list', () => {
    const occ: OccupancyResponse = {
      zones: [],
      unzoned: {
        zoneId: null,
        zoneName: 'Unzoned',
        occupied: 0,
        total: 1,
        pct: 0,
        locations: [{ id: 3, name: 'X-01', state: 'locked' }],
      },
      totals: { occupied: 0, total: 1, pct: 0 },
    };
    const z = occupancyToZoneField(occ);
    expect(z.cells).toHaveLength(1);
    expect(z.badgeText).toContain('1');
    expect(z.badgeTone).toBe('danger');
  });
});

describe('alertsToExceptions', () => {
  it('maps AlertDto severity/reason/time into ExceptionRow', () => {
    const alerts: AlertDto[] = [
      {
        id: 1,
        monitorKey: 'expiry-risk',
        monitorName: 'Expiry risk',
        severity: 'HIGH',
        status: 'FIRING',
        scope: 'lot 42',
        reason: '4 lots expiring',
        suggestedFix: 'pick soon',
        observedValue: 4,
        firstFiredAt: new Date(Date.now() - 120000).toISOString(),
        lastSeenAt: '',
        resolvedAt: null,
      },
    ];
    const rows = alertsToExceptions(alerts);
    expect(rows).toHaveLength(1);
    expect(rows[0].detail).toContain('4 lots expiring');
    expect(rows[0].severity).toBe('danger'); // HIGH -> danger
  });

  it('maps MEDIUM -> warning and LOW -> info', () => {
    const base = {
      id: 1,
      monitorKey: 'k',
      monitorName: 'Some monitor',
      status: 'FIRING' as const,
      scope: '',
      reason: 'r',
      suggestedFix: '',
      observedValue: 0,
      firstFiredAt: new Date().toISOString(),
      lastSeenAt: '',
      resolvedAt: null,
    };
    const rows = alertsToExceptions([
      { ...base, id: 2, severity: 'MEDIUM' },
      { ...base, id: 3, severity: 'LOW' },
    ]);
    expect(rows[0].severity).toBe('warning');
    expect(rows[1].severity).toBe('info');
  });

  it('returns an empty list for an empty alert array', () => {
    expect(alertsToExceptions([])).toEqual([]);
  });
});
