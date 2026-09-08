import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { useCampaigns, useCampaign, useCreateCampaign, useCloseCampaign } from './use-cycle-count';
import type { CountCampaignView } from '@/types/cycle-count';

// ---------------------------------------------------------------------------
// Campaigns card (St1) -- list + create + close + a per-campaign rollup line
// shown on expand (avoids fetching every row's rollup eagerly).
// ---------------------------------------------------------------------------

function campaignStatus(campaign: Pick<CountCampaignView, 'state'>): { label: string; tone: EntityTone } {
  if (campaign.state === 100) return { label: 'Open', tone: 'lime' };
  if (campaign.state === 700) return { label: 'Closed', tone: 'grey' };
  return { label: `State ${campaign.state}`, tone: 'grey' };
}

function CampaignRollupLine({ campaignId }: { campaignId: number }) {
  const { data, isLoading } = useCampaign(campaignId);
  if (isLoading || !data) {
    return <p className="mt-2 text-[12px] text-muted-foreground">Loading rollup…</p>;
  }
  const { sessions, ordersByState, discrepancyLines } = data;
  const totalOrders =
    ordersByState.generated + ordersByState.counted + ordersByState.finished + ordersByState.cancelled;
  return (
    <p className="mt-2 text-[12px] text-foreground/70" data-testid={`campaign-rollup-${campaignId}`}>
      {sessions} session{sessions === 1 ? '' : 's'} · {ordersByState.finished}/{totalOrders} orders finished ·{' '}
      {discrepancyLines} discrepanc{discrepancyLines === 1 ? 'y' : 'ies'}
    </p>
  );
}

function CampaignRow({ campaign }: { campaign: CountCampaignView }) {
  const [expanded, setExpanded] = useState(false);
  const closeCampaign = useCloseCampaign();
  const status = campaignStatus(campaign);

  return (
    <div className="rounded-xl border border-border p-3" data-testid={`campaign-row-${campaign.id}`}>
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          className="flex flex-1 items-baseline gap-2 text-left"
          onClick={() => setExpanded((e) => !e)}
          data-testid={`campaign-toggle-${campaign.id}`}
        >
          <span className="numeric text-[13px] font-semibold text-foreground">{campaign.campaignNumber}</span>
          <span className="text-[12.5px] text-foreground/70">{campaign.name}</span>
        </button>
        <div className="flex shrink-0 items-center gap-2">
          <StatusPill label={status.label} tone={status.tone} />
          {campaign.state === 100 && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => closeCampaign.mutate(campaign.id)}
              disabled={closeCampaign.isPending}
              data-testid={`campaign-close-${campaign.id}`}
            >
              Close
            </Button>
          )}
        </div>
      </div>
      {expanded && <CampaignRollupLine campaignId={campaign.id} />}
    </div>
  );
}

function CreateCampaignForm() {
  const createCampaign = useCreateCampaign();
  const [name, setName] = useState('');
  const [type, setType] = useState('CYCLE');

  const handleCreate = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    createCampaign.mutate({ name: trimmed, type }, { onSuccess: () => setName('') });
  };

  return (
    <div className="flex flex-wrap items-end gap-2">
      <div className="min-w-[180px] flex-1 space-y-1">
        <Label htmlFor="campaign-name" className="text-xs">
          Campaign name
        </Label>
        <Input
          id="campaign-name"
          placeholder="e.g. Q3 cycle sweep"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={100}
          data-testid="campaign-name-input"
        />
      </div>
      <div className="w-40 space-y-1">
        <Label className="text-xs">Type</Label>
        <Select value={type} onValueChange={setType}>
          <SelectTrigger data-testid="campaign-type-select">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="CYCLE">Cycle</SelectItem>
            <SelectItem value="END_OF_PERIOD">End of period</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <Button
        onClick={handleCreate}
        disabled={!name.trim() || createCampaign.isPending}
        data-testid="campaign-create-button"
      >
        New campaign
      </Button>
    </div>
  );
}

export function CampaignsCard() {
  const { data, isLoading } = useCampaigns();
  const campaigns = data ?? [];

  return (
    <SectionCard title="Count campaigns">
      <div className="space-y-3" data-testid="campaigns-card">
        <CreateCampaignForm />
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading campaigns…</p>
        ) : campaigns.length === 0 ? (
          <p className="text-sm text-muted-foreground">No campaigns yet.</p>
        ) : (
          <div className="space-y-2">
            {campaigns.map((c) => (
              <CampaignRow key={c.id} campaign={c} />
            ))}
          </div>
        )}
      </div>
    </SectionCard>
  );
}
