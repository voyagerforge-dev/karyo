/**
 * TypeScript types for the sample-data state machine.
 *
 * The lifecycle is intentionally small: idle -> loading -> loaded.
 * Reset passes through 'resetting' and returns to idle. Errors return
 * to idle with a message.
 */
import type { DemoSeedSummary } from '@/features/sample-data/sample-data-api';

export type SampleDataPhase = 'idle' | 'loading' | 'loaded' | 'resetting';

export interface SampleDataProgress {
  current: number;
  total: number;
  label: string;
}

export interface SampleDataState {
  phase: SampleDataPhase;
  progress: SampleDataProgress;
  error: string | null;
  /** Summary returned by `POST /api/v1/demo/seed`, set once phase reaches 'loaded'. */
  summary: DemoSeedSummary | null;
}

export type SampleDataAction =
  | { type: 'START' }
  | { type: 'PROGRESS'; label: string; current: number; total: number }
  | { type: 'COMPLETE'; summary: DemoSeedSummary }
  | { type: 'ERROR'; message: string }
  | { type: 'RESET_START' }
  | { type: 'RESET' }
  | { type: 'STOP' };
