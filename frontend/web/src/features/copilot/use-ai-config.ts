import { useQuery } from '@tanstack/react-query';
import { getAiConfig } from './copilot-api';

export function useAiConfig() {
  return useQuery({ queryKey: ['ai-config'], queryFn: getAiConfig, staleTime: Infinity });
}
