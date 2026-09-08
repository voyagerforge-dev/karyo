import { describe, it, expect } from 'vitest';
import { getVisibleNavItems, navigationItems } from '@/config/navigation';

/**
 * v2 INSIGHTS/OPTIMIZE/3PL items have no permission requirement yet (no backend
 * roles), so they are always visible and appear (in array order) after the
 * permission-gated items. Tests account for this ungated tail.
 */
const V2_ALWAYS = [
  'Monitors',
  'Reports',
  'Occupancy',
  'Forecasting',
  'Slotting',
  'Simulation',
];

describe('getVisibleNavItems', () => {
  it('returns the permission-gated set + always-visible items with all permissions', () => {
    const allPermissions = [
      'order-read',
      'layout-read',
      'product-read',
      'inventory-read',
      'user-admin',
    ];
    const visible = getVisibleNavItems(allPermissions);
    expect(visible.map((i) => i.title)).toEqual([
      'Control',
      'Orders',
      'ASNs',
      'Receiving',
      'Locations',
      'Items',
      'Inventory',
      'Cycle Count',
      'Users',
      ...V2_ALWAYS,
    ]);
  });

  it('returns Control + always-visible items when user has no permissions', () => {
    const visible = getVisibleNavItems([]);
    expect(visible.map((i) => i.title)).toEqual(['Control', ...V2_ALWAYS]);
  });

  it('returns Control + Locations + Items (+ always-visible) with layout-read and product-read', () => {
    const visible = getVisibleNavItems(['layout-read', 'product-read']);
    expect(visible.map((i) => i.title)).toEqual([
      'Control',
      'Locations',
      'Items',
      ...V2_ALWAYS,
    ]);
  });

  it('returns Control + Orders + ASNs + Receiving (+ always-visible) with order-read permission', () => {
    const visible = getVisibleNavItems(['order-read']);
    expect(visible.map((i) => i.title)).toEqual([
      'Control',
      'Orders',
      'ASNs',
      'Receiving',
      ...V2_ALWAYS,
    ]);
  });

  it('returns Control + Users (+ always-visible) with user-admin permission', () => {
    const visible = getVisibleNavItems(['user-admin']);
    expect(visible.map((i) => i.title)).toEqual(['Control', 'Users', ...V2_ALWAYS]);
  });

  it('Control nav item has no permission requirement', () => {
    const control = navigationItems.find((i) => i.title === 'Control');
    expect(control).toBeDefined();
    expect(control!.permission).toBeUndefined();
  });

  it('returns Waves + Streaming (with Packing and Shipments) under fulfillment-read, positioned right after Shipments', () => {
    const visible = getVisibleNavItems(['fulfillment-read']);
    expect(visible.map((i) => i.title)).toEqual([
      'Control',
      'Packing',
      'Shipments',
      'Waves',
      'Streaming',
      ...V2_ALWAYS,
    ]);
  });

  it('Waves nav item points at /waves, uses the Layers icon, and sits in the Fulfillment group', () => {
    const waves = navigationItems.find((i) => i.title === 'Waves');
    expect(waves).toBeDefined();
    expect(waves!.url).toBe('/waves');
    expect(waves!.permission).toBe('fulfillment-read');
    expect(waves!.group).toBe('Fulfillment');
  });

  it('Streaming nav item points at /streaming, uses the Zap icon, and sits in the Fulfillment group right after Waves', () => {
    const streaming = navigationItems.find((i) => i.title === 'Streaming');
    expect(streaming).toBeDefined();
    expect(streaming!.url).toBe('/streaming');
    expect(streaming!.permission).toBe('fulfillment-read');
    expect(streaming!.group).toBe('Fulfillment');

    const wavesIdx = navigationItems.findIndex((i) => i.title === 'Waves');
    const streamingIdx = navigationItems.findIndex((i) => i.title === 'Streaming');
    expect(streamingIdx).toBe(wavesIdx + 1);
  });
});
