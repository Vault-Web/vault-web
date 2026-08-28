/** What a recipient is allowed to do with a shared file or folder. */
export type SharePermission = 'VIEW' | 'DOWNLOAD' | 'EDIT';

export type SharedResourceType = 'FILE' | 'FOLDER';

/** A file or folder one Vault Web user shared with another. */
export interface ResourceShareDto {
  id: string;
  ownerUsername: string;
  recipientUsername: string;
  displayName: string;
  relativePath: string;
  resourceType: SharedResourceType;
  permissions: SharePermission[];
  createdAt: string;
  revoked: boolean;
}

export interface CreateResourceShareRequestDto {
  path: string;
  recipientUsername: string;
  permissions: SharePermission[];
}
