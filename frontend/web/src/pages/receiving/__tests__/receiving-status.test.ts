import { describe, expect, it } from 'vitest';
import { getReceiptStatus, matchesReceiptFilter, RECEIPT_LOCK_LABELS } from '../receiving-status';

describe('getReceiptStatus', () => {
  it('maps open states with tones', () => {
    expect(getReceiptStatus({ state: 50, pausedAt: null })).toEqual({ label: 'Created', tone: 'grey', open: true });
    expect(getReceiptStatus({ state: 500, pausedAt: null })).toEqual({ label: 'Receiving', tone: 'lime', open: true });
  });
  it('paused wins the label while state stays open', () => {
    expect(getReceiptStatus({ state: 500, pausedAt: '2026-07-21T00:00:00Z' })).toEqual({ label: 'Paused', tone: 'amber', open: true });
  });
  it('closed states', () => {
    expect(getReceiptStatus({ state: 700, pausedAt: null })).toEqual({ label: 'Finished', tone: 'blue', open: false });
    expect(getReceiptStatus({ state: 800, pausedAt: null })).toEqual({ label: 'Canceled', tone: 'red', open: false });
  });
  it('a closed receipt with a stale pausedAt is NOT Paused', () => {
    expect(getReceiptStatus({ state: 700, pausedAt: '2026-07-21T00:00:00Z' })).toEqual({
      label: 'Finished', tone: 'blue', open: false,
    });
  });
  it('filters: open/paused/done partition', () => {
    const started = { state: 500, pausedAt: null };
    const paused = { state: 500, pausedAt: 'x' };
    const done = { state: 700, pausedAt: null };
    expect(matchesReceiptFilter(started, 'open')).toBe(true);
    expect(matchesReceiptFilter(paused, 'open')).toBe(false);   // paused is its own chip
    expect(matchesReceiptFilter(paused, 'paused')).toBe(true);
    expect(matchesReceiptFilter(done, 'done')).toBe(true);
    expect(matchesReceiptFilter(done, 'all')).toBe(true);
  });
  it('lock labels cover exactly the receipt subset', () => {
    expect(RECEIPT_LOCK_LABELS).toEqual({ 1: 'GENERAL', 103: 'QUALITY FAULT', 202: 'LOT EXPIRED', 203: 'LOT TOO YOUNG' });
  });
});
