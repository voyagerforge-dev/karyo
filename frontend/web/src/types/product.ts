/**
 * TypeScript interfaces matching product-service DTOs.
 * @see services/product-service/karyo-product-api/src/main/kotlin/com/karyo/product/dto/
 */

export interface ItemDataNumberResponse {
  id: number;
  number: string;
  numberType: string | null;
  packagingUnitId: number | null;
  manufacturerName: string | null;
}

export interface PackagingUnitResponse {
  id: number;
  name: string;
  amount: number;
  itemUnitName: string | null;
  weight: number | null;
  height: number | null;
  width: number | null;
  depth: number | null;
  packingLevel: number;
}

export interface ProductResponse {
  id: number;
  number: string;  // SKU
  name: string;
  description: string | null;
  state: number;   // 0 = ACTIVE, 1000 = INACTIVE
  itemUnit: { id: number; name: string; unitType: string };
  scale: number;
  weight: number | null;
  height: number | null;
  width: number | null;
  depth: number | null;
  volume: number | null;
  lotMandatory: boolean;
  bestBeforeMandatory: boolean;
  shelflife: number | null;
  serialNoRecordType: string;
  defaultUnitLoadTypeId: number | null;
  defaultPackagingUnitId: number | null;
  defaultStorageStrategyId: number | null;
  zoneId: number | null;
  tradeGroup: string | null;
  imageUrl: string | null;
  numbers: ItemDataNumberResponse[];
  packagingUnits: PackagingUnitResponse[];
  created: string;
  modified: string;
}

/** Product state constants: 0=ACTIVE, 1000=INACTIVE */
export const PRODUCT_STATES = [
  { code: 0, name: 'Active' },
  { code: 1000, name: 'Inactive' },
] as const;

export interface CreateProductRequest {
  number: string;
  name: string;
  description?: string;
  itemUnitId: number;
  scale?: number;
  weight?: number;
  height?: number;
  width?: number;
  depth?: number;
  lotMandatory?: boolean;
  bestBeforeMandatory?: boolean;
  shelflife?: number;
  serialNoRecordType?: string;
  defaultUnitLoadTypeId?: number;
  defaultStorageStrategyId?: number;
  zoneId?: number;
  tradeGroup?: string;
}

export interface UpdateProductRequest {
  name?: string;
  description?: string;
  state?: number;
  weight?: number;
  height?: number;
  width?: number;
  depth?: number;
  lotMandatory?: boolean;
  bestBeforeMandatory?: boolean;
  shelflife?: number;
  serialNoRecordType?: string;
  defaultUnitLoadTypeId?: number;
  defaultPackagingUnitId?: number;
  defaultStorageStrategyId?: number;
  zoneId?: number;
  tradeGroup?: string;
  imageUrl?: string;
}

export interface ItemUnitResponse {
  id: number;
  name: string;
  unitType: string;
}

export interface CreateItemDataNumberRequest {
  number: string;
  numberType?: string;
  packagingUnitId?: number;
  manufacturerName?: string;
}
