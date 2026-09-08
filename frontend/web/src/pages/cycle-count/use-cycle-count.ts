import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type {
  CountSessionView,
  CountSessionSummaryView,
  CountOrderView,
  CountEntryView,
  StartCountRequest,
  SubmitCountRequest,
  CountCampaignView,
  CountCampaignRollupView,
  CreateCampaignRequest,
  UnitLoadMissingRequest,
} from '@/types/cycle-count';

// ---------------------------------------------------------------------------
// Query key constants
// ---------------------------------------------------------------------------

const SESSIONS_KEY = ['count-sessions'] as const;
const sessionKey = (id: number) => ['count-sessions', id] as const;
const orderKey = (id: number, view?: string) =>
  view ? ['count-orders', id, view] : (['count-orders', id] as const);
const CAMPAIGNS_KEY = ['count-campaigns'] as const;
const campaignKey = (id: number) => ['count-campaigns', id] as const;
const orderSessionKey = (id: number) => ['count-order-session', id] as const;

/** Page size for the sessions list -- generous enough that the list page's client-side search
 *  still sees "everything" in practice; a true server-side search is a follow-up, not this
 *  defect fix (defect-burndown row 8). */
const SESSIONS_PAGE_SIZE = 100;

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/**
 * Fetch a page of count sessions (summary projection -- order counts only, no nested
 * orders→lines graph; see [CountSessionSummaryView]). Fetch `useSession(id)` for the full graph.
 */
export function useSessions() {
  return useQuery({
    queryKey: SESSIONS_KEY,
    queryFn: () =>
      api
        .get<PaginatedResponse<CountSessionSummaryView>>(
          `/api/v1/count-sessions?page=0&size=${SESSIONS_PAGE_SIZE}`,
        )
        .then((page) => page.content),
    staleTime: 15_000,
  });
}

/**
 * Fetch a single count session with its embedded orders.
 */
export function useSession(id: number | undefined) {
  return useQuery({
    queryKey: sessionKey(id!),
    queryFn: () => api.get<CountSessionView>(`/api/v1/count-sessions/${id}`),
    enabled: id != null,
    staleTime: 10_000,
  });
}

/**
 * Fetch a count order in review view (plannedAmount + countedAmount visible).
 * Used by the discrepancy review panel.
 */
export function useCountOrder(id: number | undefined) {
  return useQuery({
    queryKey: orderKey(id!),
    queryFn: () => api.get<CountOrderView>(`/api/v1/count-orders/${id}`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

/**
 * Fetch a count order in blind entry view (?view=entry).
 * No plannedAmount / countedAmount shown to the counter.
 */
export function useCountOrderEntry(id: number | undefined) {
  return useQuery({
    queryKey: orderKey(id!, 'entry'),
    queryFn: () => api.get<CountEntryView>(`/api/v1/count-orders/${id}?view=entry`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

/**
 * Resolves a count order id to its owning session id -- a one-shot lookup for the `?order=`
 * deep link (from the Tasks COUNT pane's "Open in Cycle Count" link). Now that GET
 * /count-sessions returns the summary projection (no nested orders), the deep link can no
 * longer scan the sessions list to find the order's session; this fetches the order directly
 * instead. Only enabled when [id] is provided.
 */
export function useOrderSession(id: number | undefined) {
  return useQuery({
    queryKey: orderSessionKey(id!),
    queryFn: () =>
      api.get<CountOrderView>(`/api/v1/count-orders/${id}`).then((order) => order.sessionId),
    enabled: id != null,
  });
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

/**
 * Start a new count session.
 * POST /api/v1/count-sessions {type?, locationIds?, areaId?, locationNamePattern?, blindCount?}
 * Invalidates the sessions list on success.
 *
 * St5: an END_OF_PERIOD (full inventory) response may carry `skippedLocations` -- locations the
 * start walked past because they held reserved stock or an existing lock. It is NOT persisted,
 * so this response is the only place it is ever seen; surface it as a second toast rather than
 * letting it disappear on the next refetch.
 */
export function useStartCount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StartCountRequest) =>
      api.post<CountSessionSummaryView>('/api/v1/count-sessions', body),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      // M-1: starting a session creates COUNT work items -- keep the unified work
      // inbox (['work', ...] queries) fresh alongside the sessions list.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.success(`Count session ${session.sessionNumber} started`);
      const skipped = session.skippedLocations ?? [];
      if (skipped.length > 0) {
        toast.info(
          `${skipped.length} location(s) skipped (reserved or locked): ${skipped.join(', ')}`,
        );
      }
    },
  });
}

/**
 * Submit blind count entries for a count order.
 * POST /api/v1/count-orders/{id}/count {lines:[{lineId, countedAmount}]}
 * Invalidates the session list, the specific session (for progress), and the order.
 */
export function useSubmitCount(orderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SubmitCountRequest) =>
      api.post<CountOrderView>(`/api/v1/count-orders/${orderId}/count`, body),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      void queryClient.invalidateQueries({ queryKey: sessionKey(order.sessionId) });
      void queryClient.invalidateQueries({ queryKey: ['count-orders', orderId] });
      // M-1: a submitted count leaves the claimable/claimed COUNT pool (50 -> 500)
      // -- keep the unified work inbox (['work', ...] queries) fresh.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.success(`Count submitted for ${order.orderNumber}`);
    },
  });
}

/**
 * Report a whole unit load missing from the location (St4).
 * POST /api/v1/count-orders/{id}/unit-loads/missing {unitLoadId}
 * Zeroes every still-open line for that unit load; order state is unchanged, so only the
 * blind entry view (this order's ?view=entry query) needs invalidating -- unlike submit/
 * accept/recount/cancel, the session/work-inbox queries are untouched (nothing left the
 * claimable pool).
 */
export function useUnitLoadMissing(orderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (unitLoadId: number) =>
      api.post<CountEntryView>(`/api/v1/count-orders/${orderId}/unit-loads/missing`, {
        unitLoadId,
      } satisfies UnitLoadMissingRequest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKey(orderId, 'entry') });
      toast.info('Unit load reported missing -- its remaining lines are counted at 0');
    },
  });
}

/**
 * Confirm a location is empty (St3) — the sanctioned way to close out a count order whose
 * location had already gone empty by count time.
 * POST /api/v1/count-orders/{id}/location-empty (no body)
 * A zero-line order FINISHES(700) immediately; an order that has lines is zeroed and left
 * COUNTED(500) for manager review (see StocktakingService.locationEmpty KDoc for the fork).
 * Invalidation mirrors useAccept/useRecount — either branch moves the order out of GENERATED,
 * so the session list, this session, this order, and the work inbox all need a refresh.
 */
export function useLocationEmpty(orderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<CountOrderView>(`/api/v1/count-orders/${orderId}/location-empty`, {}),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      void queryClient.invalidateQueries({ queryKey: sessionKey(order.sessionId) });
      void queryClient.invalidateQueries({ queryKey: ['count-orders', orderId] });
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      if (order.state === 700) {
        toast.success(`${order.orderNumber} confirmed empty and finished`);
      } else {
        toast.info(`${order.orderNumber} counted at 0 -- awaiting manager review`);
      }
    },
  });
}

/**
 * Accept a counted order (no discrepancy action / discrepancy accepted).
 * POST /api/v1/count-orders/{id}/accept (no body)
 * Moves the order to FINISHED(700).
 */
export function useAccept(orderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<CountOrderView>(`/api/v1/count-orders/${orderId}/accept`, {}),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      void queryClient.invalidateQueries({ queryKey: sessionKey(order.sessionId) });
      void queryClient.invalidateQueries({ queryKey: ['count-orders', orderId] });
      // M-1: accepting finishes the order (FINISHED(700)) -- keep the unified work
      // inbox (['work', ...] queries) fresh.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.success(`Order ${order.orderNumber} accepted`);
    },
  });
}

/**
 * Request a recount for a counted order (discrepancy not accepted).
 * POST /api/v1/count-orders/{id}/recount (no body)
 * Resets the order back to GENERATED(50) for a fresh blind count.
 */
export function useRecount(orderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<CountOrderView>(`/api/v1/count-orders/${orderId}/recount`, {}),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      void queryClient.invalidateQueries({ queryKey: sessionKey(order.sessionId) });
      void queryClient.invalidateQueries({ queryKey: ['count-orders', orderId] });
      // M-1: a recount regenerates a GENERATED(50) order, re-entering the claimable
      // COUNT pool -- keep the unified work inbox (['work', ...] queries) fresh.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.info(`Order ${order.orderNumber} sent back for recount`);
    },
  });
}

/**
 * Cancel a count order (St7 -- location-level drop, no replacement order).
 * POST /api/v1/count-orders/{id}/cancel (no body)
 * Allowed from GENERATED(50) or COUNTED(500); moves the order to CANCELLED(800).
 * Unlike useAccept/useRecount, the order id is a mutate-time argument (not fixed at hook
 * construction) so a single hook instance can cancel any order in a rendered list -- mirrors
 * the orders module's useCancelOrder idiom (pages/orders/use-orders.ts).
 */
export function useCancelOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api.post<CountOrderView>(`/api/v1/count-orders/${orderId}/cancel`, {}),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      void queryClient.invalidateQueries({ queryKey: sessionKey(order.sessionId) });
      void queryClient.invalidateQueries({ queryKey: ['count-orders', order.id] });
      // M-1: a cancelled order leaves the claimable/claimed COUNT pool (and may close its
      // session) -- keep the unified work inbox (['work', ...] queries) fresh.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.info(`Order ${order.orderNumber} cancelled`);
    },
  });
}

// ---------------------------------------------------------------------------
// Campaign (St1)
// ---------------------------------------------------------------------------

/**
 * Fetch the list of all count campaigns (plain views, no rollup).
 * Plain array -- not paginated.
 */
export function useCampaigns() {
  return useQuery({
    queryKey: CAMPAIGNS_KEY,
    queryFn: () => api.get<CountCampaignView[]>('/api/v1/count-campaigns'),
    staleTime: 15_000,
  });
}

/**
 * Fetch a single campaign's rollup detail (session count, order-state buckets,
 * discrepancy-line count).
 */
export function useCampaign(id: number | undefined) {
  return useQuery({
    queryKey: campaignKey(id!),
    queryFn: () => api.get<CountCampaignRollupView>(`/api/v1/count-campaigns/${id}`),
    enabled: id != null,
    staleTime: 10_000,
  });
}

/**
 * Create a new count campaign.
 * POST /api/v1/count-campaigns {name, type?}
 * Invalidates the campaigns list on success.
 */
export function useCreateCampaign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCampaignRequest) =>
      api.post<CountCampaignView>('/api/v1/count-campaigns', body),
    onSuccess: (campaign) => {
      void queryClient.invalidateQueries({ queryKey: CAMPAIGNS_KEY });
      toast.success(`Campaign ${campaign.campaignNumber} created`);
    },
  });
}

/**
 * Close a campaign (refuses 409 while any child session is still OPEN).
 * POST /api/v1/count-campaigns/{id}/close (no body)
 * The campaign id is a mutate-time argument so a single hook instance can close any
 * campaign in a rendered list -- mirrors [useCancelOrder].
 */
export function useCloseCampaign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (campaignId: number) =>
      api.post<CountCampaignView>(`/api/v1/count-campaigns/${campaignId}/close`, {}),
    onSuccess: (campaign) => {
      void queryClient.invalidateQueries({ queryKey: CAMPAIGNS_KEY });
      void queryClient.invalidateQueries({ queryKey: campaignKey(campaign.id) });
      toast.success(`Campaign ${campaign.campaignNumber} closed`);
    },
  });
}
