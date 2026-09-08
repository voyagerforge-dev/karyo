import type { EntityTone } from '@/components/master-detail/tones';
import { ORDER_STATE, type DeliveryOrderResponse } from '@/types/orders';

/**
 * v3 Orders status model. Maps the backend OrderState code + line shortages
 * onto the design's 5-stage fulfillment story and the master-detail tone.
 *
 * Stage logic (README #11): exception (any short line) → red; else by stage →
 * Shipped green / Ready blue / Picking lime / Allocated amber / New grey.
 */

export type OrderStage = 'Created' | 'Allocated' | 'Picking' | 'Packed' | 'Shipped';

export const PIPELINE_STAGES: readonly OrderStage[] = [
  'Created',
  'Allocated',
  'Picking',
  'Packed',
  'Shipped',
];

/** Backend OrderState codes used by the v3 stage derivation (superset of the UI subset). */
const PACKED = 650;
const SHIPPED = 680;

export interface OrderStatusInfo {
  /** Stage index 0..4 into PIPELINE_STAGES. */
  stageIndex: number;
  /** Pill label shown in list + detail header. */
  label: string;
  /** Master-detail rail / pill tone. */
  tone: EntityTone;
  /** True when the order has at least one short line (drives the exception story). */
  exception: boolean;
}

/** Does the order have an unfulfilled (short) line? */
export function hasShortage(order: Pick<DeliveryOrderResponse, 'lines'>): boolean {
  return order.lines.some((l) => l.shortage > 0);
}

/** Map a backend state code to its pipeline stage index (0..4). */
function stageIndexForState(state: number): number {
  if (state >= SHIPPED) return 4; // Shipped / Finished
  if (state >= PACKED) return 3; // Packed
  if (state >= ORDER_STATE.PICKED) return 2; // Picking / Picked
  if (state >= ORDER_STATE.PROCESSABLE) return 1; // Allocated (reserved, processable)
  return 0; // Created / Released / Pending
}

/** Derive the full v3 status info for an order from its state + lines. */
export function getOrderStatus(
  order: Pick<DeliveryOrderResponse, 'state' | 'lines'>,
): OrderStatusInfo {
  const exception = hasShortage(order);
  const stageIndex = stageIndexForState(order.state);

  if (order.state === ORDER_STATE.CANCELED) {
    return { stageIndex: 0, label: 'Canceled', tone: 'grey', exception: false };
  }
  if (exception && order.state < SHIPPED) {
    return { stageIndex, label: 'Exception', tone: 'red', exception: true };
  }

  switch (stageIndex) {
    case 4:
      return { stageIndex, label: 'Shipped', tone: 'lime', exception: false };
    case 3:
      return { stageIndex, label: 'Ready', tone: 'blue', exception: false };
    case 2:
      return { stageIndex, label: 'Picking', tone: 'lime', exception: false };
    case 1:
      return { stageIndex, label: 'Allocated', tone: 'amber', exception: false };
    default:
      return { stageIndex, label: 'New', tone: 'grey', exception: false };
  }
}

/** Which list filter chip an order belongs to. */
export type OrderFilter = 'all' | 'Picking' | 'Exception' | 'Ready';

export function matchesFilter(info: OrderStatusInfo, filter: OrderFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'Exception') return info.exception;
  if (filter === 'Picking') return info.label === 'Picking';
  if (filter === 'Ready') return info.label === 'Ready' || info.label === 'Shipped';
  return true;
}
