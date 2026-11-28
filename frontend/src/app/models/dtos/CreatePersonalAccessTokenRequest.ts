export interface CreatePersonalAccessTokenRequest {
  name: string;
  scopes: string[];
  expiresInDays: number;
  /** Re-authentication; required for every token creation. */
  currentPassword: string;
}
