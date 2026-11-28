import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { CreatedPersonalAccessTokenDto } from '../../models/dtos/CreatedPersonalAccessTokenDto';
import { PersonalAccessTokenDto } from '../../models/dtos/PersonalAccessTokenDto';
import { AccessTokensComponent } from './access-tokens.component';

/**
 * The token page handles a credential: the secret must be shown exactly once,
 * never kept after its dialog closes, and the password must not linger.
 */
describe('AccessTokensComponent', () => {
  let component: AccessTokensComponent;
  let serviceMock: jasmine.SpyObj<{
    list: () => unknown;
    listScopes: () => unknown;
    create: (request: unknown) => unknown;
    revoke: (publicId: string) => unknown;
  }>;
  let confirmMock: jasmine.SpyObj<{ confirm: (config: unknown) => void }>;
  let toastMock: jasmine.SpyObj<{
    success: (a: string, b: string) => void;
    error: (a: string, b: string) => void;
  }>;

  const token: PersonalAccessTokenDto = {
    publicId: 'AbC123xyz789',
    name: 'backup',
    scopes: ['profile:read'],
    audience: 'core',
    createdAt: '2026-11-01T00:00:00Z',
    expiresAt: '2099-01-01T00:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    status: 'ACTIVE',
  };

  const created: CreatedPersonalAccessTokenDto = {
    token: 'vw_pat_NewTokenId01_secret',
    metadata: { ...token, publicId: 'NewTokenId01', name: 'cli' },
  };

  function fillValidForm(): void {
    component.openCreate();
    component.name = '  cli  ';
    component.toggleScope('profile:read', true);
    component.expiresInDays = 90;
    component.currentPassword = 'pw';
  }

  beforeEach(() => {
    serviceMock = jasmine.createSpyObj('PersonalAccessTokenService', [
      'list',
      'listScopes',
      'create',
      'revoke',
    ]);
    confirmMock = jasmine.createSpyObj('ConfirmationService', ['confirm']);
    toastMock = jasmine.createSpyObj('UiToastService', ['success', 'error']);
    serviceMock.list.and.returnValue(of([{ ...token }]));
    serviceMock.listScopes.and.returnValue(
      of([{ value: 'profile:read', description: 'Profile', endpoints: [] }]),
    );

    component = new AccessTokensComponent(
      serviceMock as never,
      confirmMock as never,
      toastMock as never,
    );
    component.ngOnInit();
  });

  it('loads tokens and scopes', () => {
    expect(component.tokens.length).toBe(1);
    expect(component.scopes[0].value).toBe('profile:read');
    expect(component.loading).toBeFalse();
  });

  it('requires a name, a scope and the current password before submitting', () => {
    component.openCreate();
    expect(component.canSubmit).toBeFalse();
    component.name = 'cli';
    component.toggleScope('profile:read', true);
    expect(component.canSubmit).toBeFalse();
    component.currentPassword = 'pw';
    expect(component.canSubmit).toBeTrue();
    component.toggleScope('profile:read', false);
    expect(component.canSubmit).toBeFalse();
  });

  it('creates a token, reveals the secret once and clears the password', () => {
    serviceMock.create.and.returnValue(of(created));
    fillValidForm();

    component.submitCreate();

    expect(serviceMock.create).toHaveBeenCalledWith({
      name: 'cli',
      scopes: ['profile:read'],
      expiresInDays: 90,
      currentPassword: 'pw',
    });
    expect(component.createdSecret).toBe(created.token);
    expect(component.showSecretDialog).toBeTrue();
    expect(component.showCreateDialog).toBeFalse();
    expect(component.currentPassword).toBe('');
    expect(component.tokens[0].publicId).toBe('NewTokenId01');
  });

  it('forgets the secret when the reveal dialog closes or the page is left', () => {
    serviceMock.create.and.returnValue(of(created));
    fillValidForm();
    component.submitCreate();

    component.closeSecretDialog();
    expect(component.createdSecret).toBeNull();
    expect(component.showSecretDialog).toBeFalse();

    component.createdSecret = 'vw_pat_x';
    component.ngOnDestroy();
    expect(component.createdSecret).toBeNull();
  });

  it('shows a password error on 401 and clears the password field', () => {
    serviceMock.create.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 401 })),
    );
    fillValidForm();

    component.submitCreate();

    expect(component.createError).toContain('password is incorrect');
    expect(component.currentPassword).toBe('');
    expect(component.createdSecret).toBeNull();
    expect(component.showCreateDialog).toBeTrue();
  });

  it('revokes only after confirmation and updates just that row', () => {
    confirmMock.confirm.and.callFake((config: unknown) =>
      (config as { accept: () => void }).accept(),
    );
    serviceMock.revoke.and.returnValue(
      of({ ...token, status: 'REVOKED', revokedAt: '2026-11-28T00:00:00Z' }),
    );

    component.confirmRevoke(component.tokens[0]);

    expect(serviceMock.revoke).toHaveBeenCalledWith('AbC123xyz789');
    expect(component.tokens[0].status).toBe('REVOKED');
    expect(toastMock.success).toHaveBeenCalled();
  });

  it('does not offer revocation for tokens that are no longer active', () => {
    component.confirmRevoke({ ...token, status: 'EXPIRED' });
    expect(confirmMock.confirm).not.toHaveBeenCalled();
  });

  it('copies the secret to the clipboard', async () => {
    component.createdSecret = 'vw_pat_copy_me';
    const writeText = jasmine
      .createSpy('writeText')
      .and.returnValue(Promise.resolve());
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({
      writeText,
    } as unknown as Clipboard);

    component.copySecret();
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith('vw_pat_copy_me');
    expect(component.copied).toBeTrue();
  });
});
