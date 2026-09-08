import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseAiConfig = vi.fn();
const mockUseCopilot = vi.fn();
vi.mock('@/features/copilot/use-ai-config', () => ({ useAiConfig: mockUseAiConfig }));
vi.mock('@/features/copilot/use-copilot', () => ({ useCopilot: mockUseCopilot }));
const { CopilotPanel } = await import('@/features/copilot/copilot-panel');

describe('CopilotPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseCopilot.mockReturnValue({ messages: [], send: vi.fn(), confirm: vi.fn(), isSending: false });
  });

  it('renders nothing when AI is disabled', () => {
    mockUseAiConfig.mockReturnValue({ data: { enabled: false, provider: 'none' } });
    const { container } = render(<CopilotPanel />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the launcher button when AI is enabled', () => {
    mockUseAiConfig.mockReturnValue({ data: { enabled: true, provider: 'anthropic' } });
    render(<CopilotPanel />);
    expect(screen.getByRole('button', { name: /copilot/i })).toBeInTheDocument();
  });
});
