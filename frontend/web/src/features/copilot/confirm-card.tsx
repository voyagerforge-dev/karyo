import { Button } from '@/components/ui/button';
import type { ActionProposal } from '@/types/copilot';

interface ConfirmCardProps {
  proposal: ActionProposal;
  onConfirm: (id: string) => void;
  onDecline: () => void;
}

export function ConfirmCard({ proposal, onConfirm, onDecline }: ConfirmCardProps) {
  return (
    <div className="mt-1 rounded-md border bg-card p-3 text-sm space-y-3">
      <p className="font-medium text-foreground">{proposal.summary}</p>
      <div className="flex gap-2">
        <Button size="sm" onClick={() => onConfirm(proposal.id)}>
          Approve
        </Button>
        <Button size="sm" variant="outline" onClick={onDecline}>
          Decline
        </Button>
      </div>
    </div>
  );
}
