/**
 * Sample-data context + useReducer state machine.
 *
 * Manages the sample-data lifecycle: idle -> loading -> loaded.
 * Provides loadSampleData (POST /api/v1/demo/seed -- the backend `karyo-demo`
 * engine generates a full backdated demo warehouse server-side; the endpoint
 * auto-resets first, so this is safe to call repeatedly with no manual reset
 * in between -- see `DemoResource.seed`'s KDoc), resetSampleData (POST
 * /api/v1/demo/reset -- TRUNCATE CASCADE of the operational/catalog tables),
 * and stopLoading.
 *
 * No persistence: loading is idempotent (seed auto-resets first, so
 * re-running it converges to the same data set), so there is nothing worth
 * restoring across reloads.
 */
import {
  createContext,
  useReducer,
  useEffect,
  useCallback,
  useRef,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { sampleDataApi } from '@/features/sample-data/sample-data-api';
import type {
  SampleDataState,
  SampleDataAction,
} from '@/features/sample-data/sample-data-types';

/** localStorage key used by the retired guided-tour walkthrough. */
const LEGACY_STORAGE_KEY = 'karyo-walkthrough-state';

// --- Initial state ---

const INITIAL_STATE: SampleDataState = {
  phase: 'idle',
  progress: { current: 0, total: 0, label: '' },
  error: null,
  summary: null,
};

// --- Reducer ---

function sampleDataReducer(
  state: SampleDataState,
  action: SampleDataAction,
): SampleDataState {
  switch (action.type) {
    case 'START':
      return {
        phase: 'loading',
        progress: { current: 0, total: 1, label: 'Seeding demo warehouse...' },
        error: null,
        summary: null,
      };
    case 'PROGRESS':
      return {
        ...state,
        progress: {
          current: action.current,
          total: action.total,
          label: action.label,
        },
      };
    case 'COMPLETE':
      return { ...INITIAL_STATE, phase: 'loaded', summary: action.summary };
    case 'ERROR':
      return { ...INITIAL_STATE, error: action.message };
    case 'RESET_START':
      return { ...INITIAL_STATE, phase: 'resetting' };
    case 'RESET':
    case 'STOP':
      return { ...INITIAL_STATE };
    default:
      return state;
  }
}

// --- Context ---

export interface SampleDataContextValue {
  state: SampleDataState;
  loadSampleData: () => Promise<void>;
  resetSampleData: () => Promise<void>;
  stopLoading: () => void;
}

export const SampleDataContext = createContext<SampleDataContextValue | null>(
  null,
);

// --- Provider ---

interface SampleDataProviderProps {
  children: ReactNode;
}

export function SampleDataProvider({ children }: SampleDataProviderProps) {
  const [state, dispatch] = useReducer(sampleDataReducer, INITIAL_STATE);
  const queryClient = useQueryClient();
  // The demo/seed and demo/reset calls are each a single atomic backend request
  // (no per-step network calls left to check between), so "Stop" is a soft
  // cancel: it flips this ref so the in-flight call's result is discarded
  // instead of driving a state transition once it resolves.
  const cancelledRef = useRef(false);

  // Clean up state persisted by the retired walkthrough tour.
  useEffect(() => {
    localStorage.removeItem(LEGACY_STORAGE_KEY);
  }, []);

  // --- Load sample data: generate the backdated demo warehouse server-side ---

  const loadSampleData = useCallback(async () => {
    cancelledRef.current = false;
    dispatch({ type: 'START' });

    try {
      const summary = await sampleDataApi.seed();
      if (cancelledRef.current) return;

      await queryClient.invalidateQueries();
      if (cancelledRef.current) return;

      dispatch({ type: 'COMPLETE', summary });
    } catch (err) {
      if (cancelledRef.current) return;
      const message = err instanceof Error ? err.message : 'Seeding demo data failed';
      dispatch({ type: 'ERROR', message });
    }
  }, [dispatch, queryClient]);

  // --- Stop an in-flight load ---

  const stopLoading = useCallback(() => {
    cancelledRef.current = true;
    dispatch({ type: 'STOP' });
  }, [dispatch]);

  // --- Reset sample data: TRUNCATE CASCADE server-side ---

  const resetSampleData = useCallback(async () => {
    cancelledRef.current = true; // cancel any in-flight load first
    dispatch({ type: 'RESET_START' });

    try {
      await sampleDataApi.reset();
      await queryClient.invalidateQueries();
      dispatch({ type: 'RESET' });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Resetting demo data failed';
      dispatch({ type: 'ERROR', message });
    }
  }, [dispatch, queryClient]);

  return (
    <SampleDataContext.Provider
      value={{
        state,
        loadSampleData,
        resetSampleData,
        stopLoading,
      }}
    >
      {children}
    </SampleDataContext.Provider>
  );
}
