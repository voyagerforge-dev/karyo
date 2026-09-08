import { useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { LocationResponse } from '@/types/location';

export interface PickedLocation {
  id: number;
  name: string;
}

interface LocationPickerProps {
  value: PickedLocation | null;
  onChange: (location: PickedLocation | null) => void;
  /** Unique id suffix for accessible labels / testids */
  inputId: string;
}

/**
 * Searchable location select backed by the layout locations API (one page,
 * filtered client-side as you type — same approach as ProductPicker). The
 * unfiltered list endpoint returns every location for the silo client.
 */
export function LocationPicker({ value, onChange, inputId }: LocationPickerProps) {
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const { data } = useQuery({
    queryKey: ['locations', 'picker'],
    queryFn: () =>
      api.get<PaginatedResponse<LocationResponse>>('/api/v1/locations?page=0&size=200'),
    staleTime: 30_000,
  });

  const term = search.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!data?.content || term.length < 1) return [];
    return data.content
      .filter((l) => l.name.toLowerCase().includes(term))
      .slice(0, 8);
  }, [data, term]);

  if (value) {
    return (
      <div className="flex h-9 items-center gap-2 rounded-md border bg-muted/40 px-3 text-sm">
        <span className="font-mono text-[13px]">{value.name}</span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="ml-auto size-5 shrink-0"
          onClick={() => onChange(null)}
        >
          <X className="size-3.5" />
          <span className="sr-only">Clear location</span>
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
        placeholder="Search location..."
        value={search}
        onChange={(e) => {
          setSearch(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={(e) => {
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
          {matches.map((l) => (
            <li key={l.id}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => {
                  onChange({ id: l.id, name: l.name });
                  setSearch('');
                  setOpen(false);
                }}
              >
                <span className="font-mono text-[13px]">{l.name}</span>
                <span className="truncate text-muted-foreground">
                  {l.area?.name ?? ''}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
