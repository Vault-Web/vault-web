export interface SecureSendLinkDto {
  id: string;
  filePath: string;
  fileName: string;
  shareUrl?: string;
  token?: string;
  expiresAt: string | Date;
  hasPassword?: boolean;
  isRevoked?: boolean;
  revokedAt?: string | Date | null;
  createdAt: string | Date;
}

export interface CreateSecureSendRequestDto {
  filePath: string;
  expiresAt: string;
  password?: string;
}
