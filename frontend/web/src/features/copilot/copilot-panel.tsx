import { useEffect, useRef, useState } from 'react';
import { SendHorizonal, BotMessageSquare } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet';
import { useAiConfig } from './use-ai-config';
import { useCopilot } from './use-copilot';
import { CopilotMessageBubble } from './copilot-message';
import { ConfirmCard } from './confirm-card';

const SUGGESTED_PROMPTS = [
  'Receive 100 wireless mice and put them away',
  'What\'s my warehouse occupancy?',
  'Show today\'s KPIs',
] as const;

export function CopilotPanel() {
  const cfg = useAiConfig();
  const { messages, send, confirm, isSending } = useCopilot();
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  // Track locally-declined proposals so they disappear without an API call
  const [declinedIds, setDeclinedIds] = useState<Set<string>>(new Set());
  const scrollRef = useRef<HTMLDivElement>(null);

  // Scroll to bottom whenever messages change or panel opens
  useEffect(() => {
    if (open && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, open]);

  if (!cfg.data?.enabled) return null;

  function handleSend(text: string) {
    const trimmed = text.trim();
    if (!trimmed || isSending) return;
    setInput('');
    void send(trimmed);
  }

  function handleInputKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend(input);
    }
  }

  function handleDecline(id: string) {
    setDeclinedIds((prev) => new Set(prev).add(id));
  }

  return (
    <>
      {/* Fixed launcher button — bottom-right corner */}
      <Button
        aria-label="Open copilot"
        className="fixed bottom-6 right-6 z-40 gap-2 shadow-lg"
        onClick={() => setOpen(true)}
      >
        <BotMessageSquare className="size-4" />
        Copilot
      </Button>

      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="flex flex-col w-full sm:w-[420px] sm:max-w-none p-0">
          <SheetHeader className="px-4 pt-4 pb-2 border-b">
            <SheetTitle className="flex items-center gap-2">
              <BotMessageSquare className="size-4" />
              Karyo Copilot
            </SheetTitle>
            <SheetDescription>
              Ask questions or take warehouse actions.
            </SheetDescription>
          </SheetHeader>

          {/* Message list */}
          <div
            ref={scrollRef}
            className="flex-1 overflow-y-auto px-4 py-3 space-y-3"
          >
            {messages.length === 0 && (
              <div className="text-center text-sm text-muted-foreground pt-8">
                <p className="font-medium mb-1">How can I help?</p>
                <p>Try one of the suggestions below or ask anything.</p>
              </div>
            )}

            {messages.map((msg, i) => (
              <div key={i}>
                <CopilotMessageBubble message={msg} />
                {msg.proposal && !declinedIds.has(msg.proposal.id) && (
                  <ConfirmCard
                    proposal={msg.proposal}
                    onConfirm={(id) => void confirm(id)}
                    onDecline={() => handleDecline(msg.proposal!.id)}
                  />
                )}
              </div>
            ))}

            {isSending && (
              <div className="flex justify-start">
                <span className="text-xs text-muted-foreground animate-pulse px-3 py-2">
                  Thinking…
                </span>
              </div>
            )}
          </div>

          {/* Suggested prompts — shown only when no messages yet */}
          {messages.length === 0 && (
            <div className="px-4 pb-2 flex flex-wrap gap-2">
              {SUGGESTED_PROMPTS.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  disabled={isSending}
                  onClick={() => handleSend(prompt)}
                  className="rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors disabled:opacity-50 disabled:pointer-events-none"
                >
                  {prompt}
                </button>
              ))}
            </div>
          )}

          {/* Input row */}
          <div className="border-t px-4 py-3 flex gap-2 items-center">
            <input
              type="text"
              placeholder="Ask Karyo anything…"
              value={input}
              disabled={isSending}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleInputKeyDown}
              className="flex-1 rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              aria-label="Message input"
            />
            <Button
              size="icon"
              disabled={isSending || !input.trim()}
              onClick={() => handleSend(input)}
              aria-label="Send message"
            >
              <SendHorizonal className="size-4" />
            </Button>
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
