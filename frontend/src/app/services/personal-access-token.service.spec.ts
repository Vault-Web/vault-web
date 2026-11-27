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
    service.list().subscribe();
    service.listScopes().subscribe();
    http
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/auth/tokens'))
      .flush([]);
    http
      .expectOne(
        (r) => r.method === 'GET' && r.url.endsWith('/auth/tokens/scopes'),
      )
      .flush([]);
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
