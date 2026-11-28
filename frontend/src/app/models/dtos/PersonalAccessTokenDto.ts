export type PersonalAccessTokenStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';

/** Non-secret metadata of a personal access token. Never contains the token. */
export interface PersonalAccessTokenDto {
  publicId: string;
  name: string;
  scopes: string[];
  audience: string;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  status: PersonalAccessTokenStatus;
}
