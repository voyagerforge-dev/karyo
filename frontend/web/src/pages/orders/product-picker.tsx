import { useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { ProductResponse } from '@/types/product';

export interface PickedProduct {
  id: number;
  number: string;
  name: string;
}

interface ProductPickerProps {
  value: PickedProduct | null;
  onChange: (product: PickedProduct | null) => void;
  /** Unique id suffix for accessible labels / testids */
  inputId: string;
}

/**
 * Searchable product select backed by the products API (same approach as the
 * ⌘K palette quick-search: fetch one page, filter client-side as you type).
 */
export function ProductPicker({ value, onChange, inputId }: ProductPickerProps) {
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const { data } = useQuery({
    queryKey: ['products', 'picker'],
    queryFn: () =>
      api.get<PaginatedResponse<ProductResponse>>('/api/v1/products?page=0&size=100'),
    staleTime: 30_000,
  });

  const term = search.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!data?.content || term.length < 1) return [];
    return data.content
      .filter(
        (p) =>
          p.number.toLowerCase().includes(term) ||
          p.name.toLowerCase().includes(term),
      )
      .slice(0, 8);
  }, [data, term]);

  if (value) {
    return (
      <div className="flex h-9 items-center gap-2 rounded-md border bg-muted/40 px-3 text-sm">
        <span className="font-mono text-[13px]">{value.number}</span>
        <span className="truncate text-muted-foreground">{value.name}</span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="ml-auto size-5 shrink-0"
          onClick={() => onChange(null)}
        >
          <X className="size-3.5" />
          <span className="sr-only">Clear product</span>
        </Button>
      </div>
    );
  }

  return (
    <div className="relative" ref={containerRef}>
      <Input
        id={inputId}
        role="combobox"
        aria-expanded={open && matches.length > 0}
        autoComplete="off"
        placeholder="Search product..."
        value={search}
        onChange={(e) => {
          setSearch(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={(e) => {
          // Keep the list open when clicking an option inside the container
          if (!containerRef.current?.contains(e.relatedTarget as Node)) {
            setOpen(false);
          }
        }}
      />
      {open && matches.length > 0 && (
        <ul
          role="listbox"
          className="absolute z-50 mt-1 max-h-56 w-full overflow-y-auto rounded-md border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {matches.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => {
                  onChange({ id: p.id, number: p.number, name: p.name });
                  setSearch('');
                  setOpen(false);
                }}
              >
                <span className="font-mono text-[13px]">{p.number}</span>
                <span className="truncate text-muted-foreground">{p.name}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
