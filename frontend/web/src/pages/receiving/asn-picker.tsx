import { useMemo, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useAsns } from '@/pages/asns/use-asns';
import { RECEIVING_STATE } from '@/types/receiving';

export interface PickedAsn {
  id: number;
  asnNumber: string;
}

interface AsnPickerProps {
  value: PickedAsn[];
  onChange: (asns: PickedAsn[]) => void;
  inputId: string;
}

/**
 * Searchable multi-select ASN picker limited to RELEASED/STARTED ASNs (the
 * only states a goods receipt may bind to, V424 M2M). Fetches one page,
 * filters client-side; already-picked ASNs are excluded from matches.
 */
export function AsnPicker({ value, onChange, inputId }: AsnPickerProps) {
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Pull a page of ASNs; receivable-state filtering happens client-side so a
  // single query covers both RELEASED and STARTED.
  const { data } = useAsns({ page: 0, size: 100 });

  const pickedIds = useMemo(() => new Set(value.map((a) => a.id)), [value]);

  const term = search.trim().toLowerCase();
  const matches = useMemo(() => {
    const receivable = (data?.content ?? []).filter(
      (a) =>
        (a.state === RECEIVING_STATE.RELEASED || a.state === RECEIVING_STATE.STARTED) &&
        !pickedIds.has(a.id),
    );
    if (term.length < 1) return receivable.slice(0, 8);
    return receivable
      .filter(
        (a) =>
          a.asnNumber.toLowerCase().includes(term) ||
          (a.externalNumber ?? '').toLowerCase().includes(term),
      )
      .slice(0, 8);
  }, [data, term, pickedIds]);

  function addAsn(asn: PickedAsn) {
    onChange([...value, asn]);
    setSearch('');
    setOpen(false);
  }

  function removeAsn(id: number) {
    onChange(value.filter((a) => a.id !== id));
  }

  return (
    <div className="space-y-2" ref={containerRef}>
      {value.length > 0 && (
        <div className="flex flex-wrap gap-2" data-testid="asn-picker-selected">
          {value.map((a) => (
            <div
              key={a.id}
              className="flex h-8 items-center gap-1.5 rounded-md border bg-muted/40 px-2.5 text-sm"
            >
              <span className="font-mono text-[13px]">{a.asnNumber}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-4 shrink-0"
                onClick={() => removeAsn(a.id)}
              >
                <X className="size-3" />
                <span className="sr-only">Remove {a.asnNumber}</span>
              </Button>
            </div>
          ))}
        </div>
      )}
      <div className="relative">
        <Input
          id={inputId}
          role="combobox"
          aria-expanded={open && matches.length > 0}
          autoComplete="off"
          placeholder="Search ASN (optional)..."
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
            {matches.map((a) => (
              <li key={a.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => addAsn({ id: a.id, asnNumber: a.asnNumber })}
                >
                  <span className="font-mono text-[13px]">{a.asnNumber}</span>
                  <span className="truncate text-muted-foreground">{a.carrierName ?? ''}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
