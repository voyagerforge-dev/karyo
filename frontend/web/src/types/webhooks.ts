export interface WebhookSubscription {
  id: number;
  name: string;
  targetUrl: string;
  eventTypes: string[];
  active: boolean;
}

export interface CreatedSubscription extends WebhookSubscription {
  secret: string; // present only in the create response
}

export type DeliveryStatus = 'PENDING' | 'DELIVERED' | 'FAILED' | 'DEAD';

export interface WebhookDelivery {
  id: number;
  subscriptionId: number;
  eventType: string;
  status: DeliveryStatus;
  attempts: number;
  lastResponseCode: number | null;
  lastError: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
  created: string;
}

export interface CreateSubscriptionInput {
  name: string;
  targetUrl: string;
  eventTypes: string[];
  active?: boolean;
}
