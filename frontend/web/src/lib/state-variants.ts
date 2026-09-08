/**
 * Map domain state names to semantic badge variants ("Precision" design
 * language). Used app-wide for stock/order state chips so colors come from
 * the success/warning/error CSS tokens, never per-component hex values.
 */
import type { VariantProps } from 'class-variance-authority';
import type { badgeVariants } from '@/components/ui/badge';

type BadgeVariant = NonNullable<VariantProps<typeof badgeVariants>['variant']>;

/** StockState name -> chip variant (ON_STOCK = success, INCOMING = warning, locks/holds = error). */
export function getStockStateVariant(stateName: string): BadgeVariant {
  switch (stateName) {
    case 'ON_STOCK':
    case 'PICKED':
    case 'PACKED':
    case 'SHIPPED':
      return 'success';
    case 'INCOMING':
    case 'UNDEFINED':
      return 'warning';
    case 'LOCKED':
    case 'HOLD':
    case 'QA_HOLD':
    case 'DELETABLE':
      return 'error';
    default:
      return 'outline';
  }
}

/**
 * OrderState name -> chip variant. CREATED is a draft (secondary), RELEASED is
 * in-flight (primary/blue), PROCESSABLE+ fully reserved or beyond (success),
 * PENDING marks a reservation shortfall (warning), CANCELED/FAILED terminal
 * errors (error). Everything else stays neutral.
 */
export function getOrderStateVariant(stateName: string): BadgeVariant {
  switch (stateName) {
    case 'CREATED':
      return 'secondary';
    case 'RELEASED':
      return 'default';
    case 'PROCESSABLE':
    case 'RESERVED':
    case 'FINISHED':
      return 'success';
    case 'PENDING':
      return 'warning';
    case 'CANCELED':
    case 'FAILED':
      return 'error';
    default:
      return 'outline';
  }
}

/** Humanize a state name for chip labels the same way stock chips do ("ON_STOCK" -> "ON STOCK"). */
export function humanizeStateName(stateName: string): string {
  return stateName.replace(/_/g, ' ');
}
