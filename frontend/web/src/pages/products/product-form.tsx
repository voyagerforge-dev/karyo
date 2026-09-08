import { useState } from 'react';
import { X, Plus } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  useCreateProduct,
  useUpdateProduct,
  useAddBarcode,
  useRemoveBarcode,
  useItemUnits,
} from './use-products';
import type { ProductResponse } from '@/types/product';
import { PRODUCT_STATES } from '@/types/product';

interface ProductFormProps {
  /** Existing product for edit mode; omit for create mode */
  product?: ProductResponse;
  onClose: () => void;
}

interface FormState {
  number: string;
  name: string;
  description: string;
  itemUnitId: string;
  lotMandatory: boolean;
  bestBeforeMandatory: boolean;
  shelflife: string;
  serialNoRecordType: string;
  weight: string;
  height: string;
  width: string;
  depth: string;
  scale: string;
  tradeGroup: string;
  defaultUnitLoadTypeId: string;
  defaultPackagingUnitId: string;
  defaultStorageStrategyId: string;
  zoneId: string;
  state: string;
  imageUrl: string;
}

interface FormErrors {
  number?: string;
  name?: string;
}

// Values must match the backend SerialNoRecordType enum (product module).
const SERIAL_NO_OPTIONS = [
  { value: 'NO_RECORD', label: 'None' },
  { value: 'GOODS_RECEIPT_RECORD', label: 'At goods receipt' },
  { value: 'ALWAYS_RECORD', label: 'Always' },
];

export function ProductForm({ product, onClose }: ProductFormProps) {
  const isEdit = !!product;
  const createMutation = useCreateProduct();
  const updateMutation = useUpdateProduct();
  const { data: itemUnits, isLoading: itemUnitsLoading } = useItemUnits();

  const [form, setForm] = useState<FormState>({
    number: product?.number ?? '',
    name: product?.name ?? '',
    description: product?.description ?? '',
    itemUnitId: product?.itemUnit?.id != null ? String(product.itemUnit.id) : '',
    lotMandatory: product?.lotMandatory ?? false,
    bestBeforeMandatory: product?.bestBeforeMandatory ?? false,
    shelflife: product?.shelflife != null ? String(product.shelflife) : '',
    serialNoRecordType: product?.serialNoRecordType ?? 'NO_RECORD',
    weight: product?.weight != null ? String(product.weight) : '',
    height: product?.height != null ? String(product.height) : '',
    width: product?.width != null ? String(product.width) : '',
    depth: product?.depth != null ? String(product.depth) : '',
    scale: product?.scale != null ? String(product.scale) : '',
    tradeGroup: product?.tradeGroup ?? '',
    defaultUnitLoadTypeId: product?.defaultUnitLoadTypeId != null ? String(product.defaultUnitLoadTypeId) : '',
    defaultPackagingUnitId: product?.defaultPackagingUnitId != null ? String(product.defaultPackagingUnitId) : '',
    defaultStorageStrategyId: product?.defaultStorageStrategyId != null ? String(product.defaultStorageStrategyId) : '',
    zoneId: product?.zoneId != null ? String(product.zoneId) : '',
    state: product?.state != null ? String(product.state) : '0',
    imageUrl: product?.imageUrl ?? '',
  });

  const [errors, setErrors] = useState<FormErrors>({});

  // Barcode add state (only in edit mode)
  const [newBarcodeNumber, setNewBarcodeNumber] = useState('');
  const [newBarcodeType, setNewBarcodeType] = useState('EAN13');
  const [newBarcodeManufacturer, setNewBarcodeManufacturer] = useState('');

  const addBarcodeMutation = useAddBarcode(product?.id ?? 0);
  const removeBarcodeMutation = useRemoveBarcode(product?.id ?? 0);

  function validate(): boolean {
    const newErrors: FormErrors = {};
    if (!form.number.trim()) newErrors.number = 'SKU is required';
    if (!form.name.trim()) newErrors.name = 'Name is required';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  function handleSubmit() {
    if (!validate()) return;

    if (isEdit) {
      const payload = {
        id: product.id,
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        lotMandatory: form.lotMandatory,
        bestBeforeMandatory: form.bestBeforeMandatory,
        shelflife: form.shelflife ? Number(form.shelflife) : undefined,
        serialNoRecordType: form.serialNoRecordType,
        weight: form.weight ? Number(form.weight) : undefined,
        height: form.height ? Number(form.height) : undefined,
        width: form.width ? Number(form.width) : undefined,
        depth: form.depth ? Number(form.depth) : undefined,
        defaultUnitLoadTypeId: form.defaultUnitLoadTypeId ? Number(form.defaultUnitLoadTypeId) : undefined,
        defaultPackagingUnitId: form.defaultPackagingUnitId ? Number(form.defaultPackagingUnitId) : undefined,
        defaultStorageStrategyId: form.defaultStorageStrategyId ? Number(form.defaultStorageStrategyId) : undefined,
        zoneId: form.zoneId ? Number(form.zoneId) : undefined,
        tradeGroup: form.tradeGroup.trim() || undefined,
        state: form.state ? Number(form.state) : undefined,
        imageUrl: form.imageUrl.trim() || undefined,
      };
      updateMutation.mutate(payload, { onSuccess: () => onClose() });
    } else {
      const payload = {
        number: form.number.trim(),
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        itemUnitId: form.itemUnitId ? Number(form.itemUnitId) : 1,
        scale: form.scale ? Number(form.scale) : undefined,
        lotMandatory: form.lotMandatory,
        bestBeforeMandatory: form.bestBeforeMandatory,
        shelflife: form.shelflife ? Number(form.shelflife) : undefined,
        serialNoRecordType: form.serialNoRecordType,
        weight: form.weight ? Number(form.weight) : undefined,
        height: form.height ? Number(form.height) : undefined,
        width: form.width ? Number(form.width) : undefined,
        depth: form.depth ? Number(form.depth) : undefined,
        defaultUnitLoadTypeId: form.defaultUnitLoadTypeId ? Number(form.defaultUnitLoadTypeId) : undefined,
        defaultStorageStrategyId: form.defaultStorageStrategyId ? Number(form.defaultStorageStrategyId) : undefined,
        zoneId: form.zoneId ? Number(form.zoneId) : undefined,
        tradeGroup: form.tradeGroup.trim() || undefined,
      };
      createMutation.mutate(payload, { onSuccess: () => onClose() });
    }
  }

  function handleAddBarcode() {
    if (!newBarcodeNumber.trim()) return;
    addBarcodeMutation.mutate(
      {
        number: newBarcodeNumber.trim(),
        numberType: newBarcodeType,
        manufacturerName: newBarcodeManufacturer.trim() || undefined,
      },
      {
        onSuccess: () => {
          setNewBarcodeNumber('');
          setNewBarcodeManufacturer('');
        },
      },
    );
  }

  function handleRemoveBarcode(numberId: number) {
    removeBarcodeMutation.mutate(numberId);
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-6">
      {/* SKU */}
      <div className="space-y-2">
        <Label htmlFor="product-sku">SKU</Label>
        <Input
          id="product-sku"
          value={form.number}
          onChange={(e) => {
            setForm((prev) => ({ ...prev, number: e.target.value }));
            if (errors.number) setErrors((prev) => ({ ...prev, number: undefined }));
          }}
          placeholder="e.g., SKU-001"
          disabled={isEdit}
        />
        {errors.number && (
          <p className="text-sm text-destructive">{errors.number}</p>
        )}
      </div>

      {/* Name */}
      <div className="space-y-2">
        <Label htmlFor="product-name">Name</Label>
        <Input
          id="product-name"
          value={form.name}
          onChange={(e) => {
            setForm((prev) => ({ ...prev, name: e.target.value }));
            if (errors.name) setErrors((prev) => ({ ...prev, name: undefined }));
          }}
          placeholder="Product name"
        />
        {errors.name && (
          <p className="text-sm text-destructive">{errors.name}</p>
        )}
      </div>

      {/* Description */}
      <div className="space-y-2">
        <Label htmlFor="product-description">Description</Label>
        <Textarea
          id="product-description"
          value={form.description}
          onChange={(e) =>
            setForm((prev) => ({ ...prev, description: e.target.value }))
          }
          placeholder="Optional description"
          rows={3}
        />
      </div>

      {/* Item Unit dropdown */}
      <div className="space-y-2">
        <Label>Item Unit</Label>
        <Select
          value={form.itemUnitId}
          onValueChange={(value) =>
            setForm((prev) => ({ ...prev, itemUnitId: value }))
          }
        >
          <SelectTrigger>
            <SelectValue placeholder={itemUnitsLoading ? 'Loading...' : 'Select item unit'} />
          </SelectTrigger>
          <SelectContent>
            {itemUnits?.map((unit) => (
              <SelectItem key={unit.id} value={String(unit.id)}>
                {unit.name} ({unit.unitType})
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Dimensions row */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="product-weight">Weight</Label>
          <Input
            id="product-weight"
            type="number"
            value={form.weight}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, weight: e.target.value }))
            }
            placeholder="kg"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-height">Height</Label>
          <Input
            id="product-height"
            type="number"
            value={form.height}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, height: e.target.value }))
            }
            placeholder="cm"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-width">Width</Label>
          <Input
            id="product-width"
            type="number"
            value={form.width}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, width: e.target.value }))
            }
            placeholder="cm"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-depth">Depth</Label>
          <Input
            id="product-depth"
            type="number"
            value={form.depth}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, depth: e.target.value }))
            }
            placeholder="cm"
          />
        </div>
      </div>

      {/* Lot tracking */}
      <div className="space-y-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id="product-lot"
            checked={form.lotMandatory}
            onCheckedChange={(checked) =>
              setForm((prev) => ({ ...prev, lotMandatory: checked === true }))
            }
          />
          <Label htmlFor="product-lot">Lot Mandatory</Label>
        </div>
        <div className="flex items-center gap-2">
          <Checkbox
            id="product-bestbefore"
            checked={form.bestBeforeMandatory}
            onCheckedChange={(checked) =>
              setForm((prev) => ({
                ...prev,
                bestBeforeMandatory: checked === true,
              }))
            }
          />
          <Label htmlFor="product-bestbefore">Best Before Mandatory</Label>
        </div>
        {form.lotMandatory && (
          <div className="space-y-2">
            <Label htmlFor="product-shelflife">Shelf Life (days)</Label>
            <Input
              id="product-shelflife"
              type="number"
              value={form.shelflife}
              onChange={(e) =>
                setForm((prev) => ({ ...prev, shelflife: e.target.value }))
              }
              placeholder="Optional"
            />
          </div>
        )}
      </div>

      {/* Serial number record type */}
      <div className="space-y-2">
        <Label>Serial Number Recording</Label>
        <Select
          value={form.serialNoRecordType}
          onValueChange={(value) =>
            setForm((prev) => ({ ...prev, serialNoRecordType: value }))
          }
        >
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SERIAL_NO_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Scale */}
      <div className="space-y-2">
        <Label htmlFor="product-scale">Scale</Label>
        <Input
          id="product-scale"
          type="number"
          value={form.scale}
          onChange={(e) =>
            setForm((prev) => ({ ...prev, scale: e.target.value }))
          }
          placeholder="Decimal places"
        />
      </div>

      {/* Trade Group */}
      <div className="space-y-2">
        <Label htmlFor="product-tradegroup">Trade Group</Label>
        <Input
          id="product-tradegroup"
          value={form.tradeGroup}
          onChange={(e) =>
            setForm((prev) => ({ ...prev, tradeGroup: e.target.value }))
          }
          placeholder="e.g., FOOD, ELECTRONICS"
        />
      </div>

      {/* Defaults row: UL Type, Strategy, Zone */}
      <div className="grid grid-cols-3 gap-4">
        <div className="space-y-2">
          <Label htmlFor="product-ultype">UL Type</Label>
          <Input
            id="product-ultype"
            type="number"
            value={form.defaultUnitLoadTypeId}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, defaultUnitLoadTypeId: e.target.value }))
            }
            placeholder="Unit load type ID"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-strategy">Strategy</Label>
          <Input
            id="product-strategy"
            type="number"
            value={form.defaultStorageStrategyId}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, defaultStorageStrategyId: e.target.value }))
            }
            placeholder="Storage strategy ID"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-zone">Zone</Label>
          <Input
            id="product-zone"
            type="number"
            value={form.zoneId}
            onChange={(e) =>
              setForm((prev) => ({ ...prev, zoneId: e.target.value }))
            }
            placeholder="Zone ID"
          />
        </div>
      </div>

      {/* Edit-mode only: State and Image URL */}
      {isEdit && (
        <>
          <div className="space-y-2">
            <Label>State</Label>
            <Select
              value={form.state}
              onValueChange={(value) =>
                setForm((prev) => ({ ...prev, state: value }))
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PRODUCT_STATES.map((s) => (
                  <SelectItem key={s.code} value={String(s.code)}>
                    {s.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="product-imageurl">Image URL</Label>
            <Input
              id="product-imageurl"
              value={form.imageUrl}
              onChange={(e) =>
                setForm((prev) => ({ ...prev, imageUrl: e.target.value }))
              }
              placeholder="https://..."
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="product-defaultpackagingunit">Default packaging unit</Label>
            <Select
              value={form.defaultPackagingUnitId || 'none'}
              onValueChange={(value) =>
                setForm((prev) => ({
                  ...prev,
                  defaultPackagingUnitId: value === 'none' ? '' : value,
                }))
              }
            >
              <SelectTrigger id="product-defaultpackagingunit">
                <SelectValue placeholder="None" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">None</SelectItem>
                {product.packagingUnits.map((pu) => (
                  <SelectItem key={pu.id} value={String(pu.id)}>
                    {pu.name} — {pu.amount} × {pu.itemUnitName ?? ''}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </>
      )}

      {/* Barcode section -- edit mode only */}
      {isEdit && (
        <div className="space-y-3">
          <h3 className="text-sm font-medium">Barcodes</h3>
          <div className="flex flex-wrap gap-2">
            {product.numbers.map((barcode) => (
              <Badge key={barcode.id} variant="secondary" className="gap-1 pr-1">
                {barcode.number}
                {barcode.numberType && (
                  <span className="text-muted-foreground">
                    ({barcode.numberType})
                  </span>
                )}
                {barcode.manufacturerName && (
                  <span className="text-muted-foreground">
                    · {barcode.manufacturerName}
                  </span>
                )}
                <button
                  type="button"
                  className="ml-1 rounded-full hover:bg-muted"
                  onClick={() => handleRemoveBarcode(barcode.id)}
                >
                  <X className="size-3" />
                  <span className="sr-only">Remove {barcode.number}</span>
                </button>
              </Badge>
            ))}
          </div>
          <div className="flex items-end gap-2">
            <div className="flex-1 space-y-1">
              <Label htmlFor="new-barcode">Number</Label>
              <Input
                id="new-barcode"
                value={newBarcodeNumber}
                onChange={(e) => setNewBarcodeNumber(e.target.value)}
                placeholder="Barcode number"
              />
            </div>
            <div className="w-28 space-y-1">
              <Label>Type</Label>
              <Select value={newBarcodeType} onValueChange={setNewBarcodeType}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="EAN13">EAN13</SelectItem>
                  <SelectItem value="UPC">UPC</SelectItem>
                  <SelectItem value="CODE128">CODE128</SelectItem>
                  <SelectItem value="CUSTOM">Custom</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex-1 space-y-1">
              <Label htmlFor="new-barcode-manufacturer">Manufacturer</Label>
              <Input
                id="new-barcode-manufacturer"
                value={newBarcodeManufacturer}
                onChange={(e) => setNewBarcodeManufacturer(e.target.value)}
                placeholder="Optional"
              />
            </div>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={handleAddBarcode}
              disabled={addBarcodeMutation.isPending}
            >
              <Plus className="size-4" />
              Add
            </Button>
          </div>
        </div>
      )}

      {/* Submit button */}
      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={handleSubmit} disabled={isSubmitting}>
          {isSubmitting ? 'Saving...' : 'Save'}
        </Button>
      </div>
    </div>
  );
}
