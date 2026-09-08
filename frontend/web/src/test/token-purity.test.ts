import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/** Dirs swept to semantic tokens. */
const SCAN_DIRS = ['src/components', 'src/pages', 'src/features'];

/** file → substrings that are allowed to keep theme-invariant colors. */
const ALLOW: Record<string, string[]> = {
  'src/components/ui/dialog.tsx': ['bg-black/50'],
  'src/components/ui/alert-dialog.tsx': ['bg-black/50'],
  'src/components/ui/sheet.tsx': ['bg-black/50'],
  'src/components/ui/button.tsx': ['text-white'],
  'src/components/ui/badge.tsx': ['text-white'],
  'src/components/layout/app-header.tsx': ['text-white'], // count bubble on bg-destructive
  // TONE_COLOR is a documented Task 4 deferral (out-of-scope .ts data file);
  // consumed by location-detail.tsx's OccupancyRing. Only the `lime` entry
  // happens to match the bare-hex alternative in FORBIDDEN; the rest of the
  // map (blue/amber/red/violet/grey) isn't caught by the regex at all.
  'src/components/master-detail/tones.ts': ['#C7F24E'],
};

const FORBIDDEN = /(?:bg|text|border|ring|from|to|via|fill|stroke)-\[#[0-9a-fA-F]{3,8}\]|#14120D|#1B1813|#C7F24E/;

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return name === '__tests__' ? [] : tsxFiles(p);
    if (name.includes('.test.')) return [];
    return name.endsWith('.tsx') || name.endsWith('.ts') ? [p] : [];
  });
}

describe('token purity (light-mode safety)', () => {
  for (const dir of SCAN_DIRS) {
    for (const file of tsxFiles(dir)) {
      it(`${file} uses semantic tokens only`, () => {
        const allowed = ALLOW[file.replaceAll('\\', '/')] ?? [];
        const offending = readFileSync(file, 'utf8')
          .split('\n')
          .map((line, i) => ({ line, n: i + 1 }))
          .filter(({ line }) => FORBIDDEN.test(line) && !allowed.some((a) => line.includes(a)));
        expect(offending).toEqual([]);
      });
    }
  }
});
