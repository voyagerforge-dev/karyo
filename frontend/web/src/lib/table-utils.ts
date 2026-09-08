import type { SortingState } from '@tanstack/react-table';

/**
 * Convert TanStack Table SortingState to backend sort parameter string.
 * @example [{ id: 'name', desc: false }] -> 'name,asc'
 * @example [{ id: 'created', desc: true }] -> 'created,desc'
 * @returns string like "field,asc" or undefined if no sorting applied
 */
export function sortingStateToString(
  sorting: SortingState,
): string | undefined {
  if (sorting.length === 0) return undefined;
  const { id, desc } = sorting[0];
  return `${id},${desc ? 'desc' : 'asc'}`;
}
