import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type {
  ProductResponse,
  CreateProductRequest,
  UpdateProductRequest,
  ItemUnitResponse,
  CreateItemDataNumberRequest,
} from '@/types/product';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
}

/**
 * Fetch paginated products with server-side sort/pagination.
 */
export function useProducts(options: UseListOptions) {
  const { page, size, sort } = options;
  return useQuery({
    queryKey: ['products', { page, size, sort }],
    queryFn: () =>
      api.get<PaginatedResponse<ProductResponse>>(
        `/api/v1/products?page=${page}&size=${size}${sort ? `&sort=${sort}` : ''}`,
      ),
    staleTime: 30_000,
  });
}

/**
 * Create a new product.
 */
export function useCreateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateProductRequest) =>
      api.post<ProductResponse>('/api/v1/products', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      toast.success('Product created');
    },
  });
}

/**
 * Fetch all item units (flat array, not paginated).
 */
export function useItemUnits() {
  return useQuery({
    queryKey: ['item-units'],
    queryFn: () => api.get<ItemUnitResponse[]>('/api/v1/item-units'),
    staleTime: 60_000,
  });
}

/**
 * Update an existing product. Uses UpdateProductRequest (all fields optional).
 */
export function useUpdateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...data }: UpdateProductRequest & { id: number }) =>
      api.put<ProductResponse>(`/api/v1/products/${id}`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      toast.success('Product updated');
    },
  });
}

/**
 * Add a barcode (ItemDataNumber) to a product.
 */
export function useAddBarcode(productId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateItemDataNumberRequest) =>
      api.post<void>(`/api/v1/products/${productId}/numbers`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      toast.success('Barcode added');
    },
  });
}

/**
 * Remove a barcode (ItemDataNumber) from a product.
 */
export function useRemoveBarcode(productId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (numberId: number) =>
      api.delete<void>(`/api/v1/products/${productId}/numbers/${numberId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      toast.success('Barcode removed');
    },
  });
}
