import type { EntityTone } from '@/components/master-detail/tones';
import type { Shipment } from '@/types/shipments';

export interface ShipmentStatusInfo {
  label: string;
  tone: EntityTone;
}

/**
 * Shipment state -> label/tone (Control master-detail palette). Single
 * source of truth: the shipments page (Task 2) imports this same function
 * rather than re-deriving it -- mirrors how use-shipping re-exports
 * useShipments/useShipment from use-packing.
 */
export function getShipmentStatus(s: Pick<Shipment, 'state'>): ShipmentStatusInfo {
  switch (s.state) {
    case 640:
      return { label: 'Packing', tone: 'amber' };
    case 650:
      return { label: 'Packed', tone: 'blue' };
    case 670:
      return { label: 'Shipping', tone: 'lime' };
    case 680:
      return { label: 'Shipped', tone: 'grey' };
    case 800:
      return { label: 'Canceled', tone: 'red' };
    default:
      return { label: String(s.state), tone: 'grey' };
  }
}
