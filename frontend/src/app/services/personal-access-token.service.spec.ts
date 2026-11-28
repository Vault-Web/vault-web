import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { PersonalAccessTokenService } from './personal-access-token.service';

describe('PersonalAccessTokenService', () => {
  let service: PersonalAccessTokenService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PersonalAccessTokenService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends the creation request with re-authentication in the body, not the URL', () => {
    service
      .create({
        name: 'cli',
        scopes: ['profile:read'],
        expiresInDays: 30,
        currentPassword: 'pw',
      })
      .subscribe();
    const request = http.expectOne((r) => r.url.endsWith('/auth/tokens'));
    expect(request.request.method).toBe('POST');
    expect(request.request.urlWithParams).not.toContain('pw');
    expect(request.request.body.currentPassword).toBe('pw');
    request.flush({});
  });

  it('lists tokens and supported scopes', () => {
    let tokens: unknown[] | undefined;
    let scopes: unknown[] | undefined;
    service.list().subscribe((result) => (tokens = result));
    service.listScopes().subscribe((result) => (scopes = result));
    http
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/auth/tokens'))
      .flush([{ publicId: 'AbC123xyz789' }]);
    http
      .expectOne(
        (r) => r.method === 'GET' && r.url.endsWith('/auth/tokens/scopes'),
      )
      .flush([{ value: 'profile:read' }]);
    expect(tokens).toEqual([{ publicId: 'AbC123xyz789' }]);
    expect(scopes).toEqual([{ value: 'profile:read' }]);
  });

  it('revokes by public id', () => {
    service.revoke('AbC123xyz789').subscribe();
    const request = http.expectOne((r) =>
      r.url.endsWith('/auth/tokens/AbC123xyz789'),
    );
    expect(request.request.method).toBe('DELETE');
    request.flush({});
  });
});
