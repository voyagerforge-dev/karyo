import { useRef, useState } from 'react';
import type { CopilotMessage } from '@/types/copilot';
import { confirmProposal, sendChat } from './copilot-api';

export function useCopilot() {
  const sessionId = useRef(crypto.randomUUID());
  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [isSending, setIsSending] = useState(false);

  async function send(text: string) {
    setIsSending(true);
    setMessages((prev) => [...prev, { role: 'user', text }]);
    try {
      const response = await sendChat(sessionId.current, text);
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', text: response.message, proposal: response.proposal },
      ]);
    } catch (err: unknown) {
      const message =
        err instanceof Error && err.message
          ? err.message
          : "That action couldn't be completed.";
      setMessages((prev) => [...prev, { role: 'assistant', text: message }]);
    } finally {
      setIsSending(false);
    }
  }

  async function confirm(id: string) {
    setIsSending(true);
    // Clear the proposal on the message that carries this proposal id
    setMessages((prev) =>
      prev.map((m) =>
        m.proposal?.id === id ? { ...m, proposal: null } : m,
      ),
    );
    try {
      const response = await confirmProposal(id);
      setMessages((prev) => [...prev, { role: 'assistant', text: response.message }]);
    } catch (err: unknown) {
      const message =
        err instanceof Error && err.message
          ? err.message
          : "That action couldn't be completed.";
      setMessages((prev) => [...prev, { role: 'assistant', text: message }]);
    } finally {
      setIsSending(false);
    }
  }

  return { messages, send, confirm, isSending };
}
