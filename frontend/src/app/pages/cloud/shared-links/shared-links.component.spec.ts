import { of, throwError } from 'rxjs';
import { ResourceShareDto } from '../../../models/dtos/ResourceShareDto';
import { SecureSendLinkDto } from '../../../models/dtos/SecureSendLinkDto';
import { SharedLinksComponent } from './shared-links.component';

/**
 * The page where a user withdraws sharing they handed out. Both kinds are
 * destructive and irreversible, so each one has to pass the confirmation dialog
 * and only touch its own row.
 */
describe('SharedLinksComponent', () => {
  let component: SharedLinksComponent;
  let cloudMock: jasmine.SpyObj<{
    listSecureSendLinks: () => unknown;
    revokeSecureSendLink: (id: string) => unknown;
  }>;
  let shareMock: jasmine.SpyObj<{
    listOwned: () => unknown;
    revoke: (id: string) => unknown;
  }>;
  let confirmMock: jasmine.SpyObj<{ confirm: (config: unknown) => void }>;
  let toastMock: jasmine.SpyObj<{
    success: (a: string, b: string) => void;
    error: (a: string, b: string) => void;
  }>;

  const link: SecureSendLinkDto = {
    id: 'link1',
    filePath: '/a.txt',
    fileName: 'a.txt',
    expiresAt: '2099-01-01T00:00:00Z',
    createdAt: '2026-07-20T00:00:00Z',
    isRevoked: false,
  };

  const share: ResourceShareDto = {
    id: 'share1',
    ownerUsername: 'alice',
    recipientUsername: 'bob',
    displayName: 'project',
    relativePath: 'project',
    resourceType: 'FOLDER',
    permissions: ['VIEW', 'DOWNLOAD'],
    createdAt: '2026-07-20T00:00:00Z',
    revoked: false,
  };

  /** Answers the next confirmation with "accept", as a user clicking through would. */
  function acceptNextConfirmation(): void {
    confirmMock.confirm.and.callFake((config: unknown) =>
      (config as { accept: () => void }).accept(),
    );
  }

  beforeEach(() => {
    cloudMock = jasmine.createSpyObj('CloudService', [
      'listSecureSendLinks',
      'revokeSecureSendLink',
    ]);
    shareMock = jasmine.createSpyObj('ResourceShareService', [
      'listOwned',
      'revoke',
    ]);
    confirmMock = jasmine.createSpyObj('ConfirmationService', ['confirm']);
    toastMock = jasmine.createSpyObj('UiToastService', ['success', 'error']);

    cloudMock.listSecureSendLinks.and.returnValue(of([{ ...link }]));
    shareMock.listOwned.and.returnValue(of([{ ...share }]));

    component = new SharedLinksComponent(
      cloudMock as never,
      shareMock as never,
      confirmMock as never,
      toastMock as never,
      {} as never,
    );
  });

  it('loads both kinds of sharing on init', () => {
    component.ngOnInit();

    expect(component.sent.length).toBe(1);
    expect(component.links.length).toBe(1);
    expect(component.loading).toBeFalse();
    expect(component.error).toBeUndefined();
  });

  it('reports a failed load instead of showing an empty page', () => {
    cloudMock.listSecureSendLinks.and.returnValue(
      throwError(() => ({ status: 500 })),
    );

    component.ngOnInit();

    expect(component.error).toBe('Error loading shares');
    expect(component.loading).toBeFalse();
  });

  it('revokes a link only after confirmation and marks it revoked', () => {
    component.ngOnInit();
    const loaded = component.links[0];
    cloudMock.revokeSecureSendLink.and.returnValue(of(void 0));

    component.confirmRevokeLink(loaded);
    expect(cloudMock.revokeSecureSendLink).not.toHaveBeenCalled();

    acceptNextConfirmation();
    component.confirmRevokeLink(loaded);

    expect(cloudMock.revokeSecureSendLink).toHaveBeenCalledWith('link1');
    expect(loaded.isRevoked).toBeTrue();
    expect(component.linkStatus(loaded)).toBe('revoked');
    expect(component.isProcessing('link1')).toBeFalse();
    expect(toastMock.success).toHaveBeenCalledWith(
      'Link revoked',
      'The share link for "a.txt" no longer works.',
    );
  });

  it('drops a revoked member share from the list', () => {
    component.ngOnInit();
    shareMock.revoke.and.returnValue(of(void 0));
    acceptNextConfirmation();

    component.confirmRevokeShare(component.sent[0]);

    expect(shareMock.revoke).toHaveBeenCalledWith('share1');
    expect(component.sent).toEqual([]);
    expect(component.isProcessing('share1')).toBeFalse();
  });

  it('keeps the share listed when revoking fails', () => {
    component.ngOnInit();
    shareMock.revoke.and.returnValue(throwError(() => ({ status: 500 })));
    acceptNextConfirmation();

    component.confirmRevokeShare(component.sent[0]);

    expect(component.sent.length).toBe(1);
    expect(component.isProcessing('share1')).toBeFalse();
    expect(toastMock.error).toHaveBeenCalledWith(
      'Revocation failed',
      'Access was not changed.',
    );
  });
});
