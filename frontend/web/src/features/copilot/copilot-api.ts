import { api } from '@/lib/api-client';
import type { AiConfig, ChatResponse, ConfirmResponse } from '@/types/copilot';

export const getAiConfig = () => api.get<AiConfig>('/api/v1/ai/config');

export const sendChat = (sessionId: string, message: string) =>
  api.post<ChatResponse>('/api/v1/ai/chat', { sessionId, message });

export const confirmProposal = (id: string) =>
  api.post<ConfirmResponse>(`/api/v1/ai/confirm/${id}`, {});
