export interface ActionProposal {
  id: string;
  summary: string;
  toolName: string;
}

export interface ChatResponse {
  message: string;
  proposal: ActionProposal | null;
}

export interface ConfirmResponse {
  message: string;
}

export interface AiConfig {
  enabled: boolean;
  provider: string;
}

export interface CopilotMessage {
  role: 'user' | 'assistant';
  text: string;
  proposal?: ActionProposal | null;
}
