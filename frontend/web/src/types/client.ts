/**
 * TypeScript interfaces matching karyo-auth ClientDtos.
 * @see services/auth-service/karyo-auth-api/src/main/kotlin/com/karyo/auth/dto/ClientDtos.kt
 */

/** Goods-owner (myWMS Client) — auth module. GET/POST/PUT /api/v1/clients. */
export type ClientState = 'ACTIVE' | 'INACTIVE';

export interface ClientResponse {
  id: number;
  name: string;
  number: string;
  code: string;
  email: string;
  phone: string;
  fax: string;
  state: ClientState;
  isSystemClient: boolean;
}

export interface CreateClientRequest {
  name: string;
  number: string;
  code?: string;
  email?: string;
  phone?: string;
  fax?: string;
}

/** No `number` — the business key is immutable. */
export interface UpdateClientRequest {
  name: string;
  code?: string;
  email?: string;
  phone?: string;
  fax?: string;
}

export interface ClientConsistencyReport {
  danglingClientIds: number[];
  checkedAt: string;
}
