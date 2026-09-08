/**
 * Custom hook for accessing the sample-data context.
 * Must be used within a SampleDataProvider.
 */
import { useContext } from 'react';
import { SampleDataContext } from '@/features/sample-data/sample-data-provider';
import type { SampleDataContextValue } from '@/features/sample-data/sample-data-provider';

export function useSampleData(): SampleDataContextValue {
  const ctx = useContext(SampleDataContext);
  if (!ctx) {
    throw new Error('useSampleData must be used within a SampleDataProvider');
  }
  return ctx;
}
