import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { ProductForm } from '@/pages/products/product-form';
import type { ProductResponse } from '@/types/product';

interface ItemFormSheetProps {
  /** Sheet visibility — items-page owns this via formProduct !== undefined. */
  open: boolean;
  /** Existing product to edit; omit to create a new item. */
  product?: ProductResponse;
  onOpenChange: (open: boolean) => void;
}

/**
 * Right-side Sheet wrapping the existing `ProductForm` CRUD (create/edit),
 * reused as-is from the retired /products page. `ProductForm` already owns
 * its Cancel/Save row, so the Sheet needs no separate footer.
 */
export function ItemFormSheet({ open, product, onOpenChange }: ItemFormSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto sm:w-[45vw] sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>{product ? 'Edit item' : 'New item'}</SheetTitle>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto px-4 pb-6">
          <ProductForm product={product} onClose={() => onOpenChange(false)} />
        </div>
      </SheetContent>
    </Sheet>
  );
}
