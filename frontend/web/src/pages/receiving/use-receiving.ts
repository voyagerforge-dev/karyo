import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type {
  GoodsReceiptResponse,
  CreateGoodsReceiptRequest,
  ReceiveLineRequest,
  ReceiveLineResponse,
  UpdateGoodsReceiptRequest,
} from '@/types/receiving';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
  /** OrderState code filter (server-side ?state=) */
  state?: number;
  /** Restrict to receipts bound to a given ASN (server-side ?asnId=) */
  asnId?: number;
}

/** Fetch paginated goods receipts with server-side sort/pagination/filter. */
export function useGoodsReceipts(options: UseListOptions) {
  const { page, size, sort, state, asnId } = options;
  return useQuery({
    queryKey: ['goods-receipts', { page, size, sort, state, asnId }],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (sort) params.set('sort', sort);
      if (state != null) params.set('state', String(state));
      if (asnId != null) params.set('asnId', String(asnId));
      return api.get<PaginatedResponse<GoodsReceiptResponse>>(
        `/api/v1/goods-receipts?${params.toString()}`,
      );
    },
    staleTime: 10_000,
  });
}

/** Fetch a single goods receipt (lines incl. stockUnitId/location/qaHold). */
export function useGoodsReceipt(id: number | undefined) {
  return useQuery({
    queryKey: ['goods-receipts', 'detail', id],
    queryFn: () => api.get<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}`),
    enabled: id != null,
    staleTime: 2_000,
  });
}

function useInvalidateReceipts() {
  const queryClient = useQueryClient();
  return (id?: number) => {
    queryClient.invalidateQueries({ queryKey: ['goods-receipts'] });
    if (id != null) queryClient.invalidateQueries({ queryKey: ['goods-receipts', 'detail', id] });
    // Receiving against an ASN bumps its progress; keep ASN views fresh too.
    queryClient.invalidateQueries({ queryKey: ['asns'] });
  };
}

/**
 * Open a goods receipt (optionally bound to a RELEASED/STARTED ASN). 409
 * 'asn-not-receivable' is surfaced to the caller via ApiError.
 */
export function useCreateGoodsReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (data: CreateGoodsReceiptRequest) =>
      api.post<GoodsReceiptResponse>('/api/v1/goods-receipts', data),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
      toast.success(`Receipt ${receipt.receiptNumber} opened`);
    },
  });
}

/**
 * Receive one line. 409 'over-receipt' (when amount exceeds expectation without
 * allowOverReceipt) bubbles up as an ApiError so the workbench can offer an
 * inline confirm-and-retry.
 */
export function useReceiveLine() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: ({ id, ...data }: ReceiveLineRequest & { id: number }) =>
      api.post<ReceiveLineResponse>(`/api/v1/goods-receipts/${id}/lines`, data),
    onSuccess: (result) => {
      invalidate(result.receipt.id);
    },
  });
}

/** Finish a receipt: non-QA-held stock INCOMING→ON_STOCK; QA-held stays INCOMING. */
export function useFinishReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/finish`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
      const held = receipt.lines.some((l) => l.qaHold);
      toast.success(
        held
          ? `Receipt ${receipt.receiptNumber} finished — stock moved to ON STOCK (locked lines remain held)`
          : `Receipt ${receipt.receiptNumber} finished — stock moved to ON STOCK`,
      );
    },
  });
}

/** Cancel an empty receipt (zero lines only; backend 409 otherwise). */
export function useCancelReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/cancel`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
      toast.success(`Receipt ${receipt.receiptNumber} canceled`);
    },
  });
}

/** Update receipt metadata (prio, receiptDate, dock). */
export function useUpdateGoodsReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: ({ id, ...data }: UpdateGoodsReceiptRequest & { id: number }) =>
      api.put<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}`, data),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
    },
  });
}

/** Claim a receipt (lock it for receiving by this operator). */
export function useClaimReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/claim`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
    },
  });
}

/** Release a receipt (clear the claim lock). */
export function useReleaseReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/release`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
    },
  });
}

/** Pause a receipt (temporarily suspend receiving). */
export function usePauseReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/pause`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
    },
  });
}

/** Resume a receipt (clear the pause). */
export function useResumeReceipt() {
  const invalidate = useInvalidateReceipts();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/resume`, {}),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
    },
  });
}

/**
 * B3: reverses one received line — deletes its stock and decrements the bound
 * ASN line back down (409 refusals: receipt closed/paused, line already
 * reversed, stock amount changed since receiving, stock reserved, or its
 * putaway already started — all surfaced by the api-client's global toast).
 * Reversal also cancels the line's still-pending PUTAWAY task server-side, so
 * work-pool membership can change alongside the receiving keys — this needs
 * the `['work']` invalidation on top of `useInvalidateReceipts` (the doctrine
 * applied everywhere a mutation can affect the unified work inbox).
 */
export function useReverseLine() {
  const invalidate = useInvalidateReceipts();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, lineId }: { id: number; lineId: number }) =>
      api.delete<GoodsReceiptResponse>(`/api/v1/goods-receipts/${id}/lines/${lineId}`),
    onSuccess: (receipt) => {
      invalidate(receipt.id);
      queryClient.invalidateQueries({ queryKey: ['work'] });
      toast.success(`Line reversed on ${receipt.receiptNumber}`);
    },
  });
}
