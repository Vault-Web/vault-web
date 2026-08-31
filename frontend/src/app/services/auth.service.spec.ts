import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let routerSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        AuthService,
        {
          provide: Router,
          useValue: { navigate: jasmine.createSpy('navigate') },
        },
      ],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    routerSpy = spyOn(TestBed.inject(Router), 'navigate');
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  describe('isLoggedInLocally()', () => {
    it('should return false when no token exists', () => {
      expect(service.isLoggedInLocally()).toBe(false);
    });

    it('should return false when token cannot be decoded', () => {
      service.saveToken('invalid.token');
      expect(service.isLoggedInLocally()).toBe(false);
    });

    it('should return true for token with no exp claim', () => {
      const token =
        'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.TJVA95OrM7E2cBab30RMHrHDcEfxjoYZgeFONFh7HgQ';
      service.saveToken(token);
      expect(service.isLoggedInLocally()).toBe(true);
    });

    it('should return true for non-expired token with exp claim', () => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600; // 1 hour in future
      const payload = { sub: 'user1', exp: futureExp };
      const token = createJwt(payload);
      service.saveToken(token);
      expect(service.isLoggedInLocally()).toBe(true);
    });

    it('should return false for expired token', () => {
      const pastExp = Math.floor(Date.now() / 1000) - 3600; // 1 hour in past
      const payload = { sub: 'user1', exp: pastExp };
      const token = createJwt(payload);
      service.saveToken(token);
      expect(service.isLoggedInLocally()).toBe(false);
    });
  });

  describe('validateTokenWithServer()', () => {
    it('should return false when no token exists locally', (done) => {
      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        done();
      });
    });

    it('should call /auth/refresh and update token on success', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const oldToken = createJwt({ sub: 'user1', exp: futureExp });
      const newToken = createJwt({ sub: 'user1', exp: futureExp + 7200 });
      service.saveToken(oldToken);

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(true);
        expect(service.getToken()).toBe(newToken);
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      expect(req.request.method).toBe('POST');
      req.flush({ token: newToken });
    });

    it('should clear auth state and return false when server responds with 401', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const token = createJwt({ sub: 'user1', exp: futureExp });
      service.saveToken(token);
      service.saveUsername('testuser');

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        expect(localStorage.getItem('token')).toBeNull();
        expect(localStorage.getItem('username')).toBeNull();
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      req.flush(null, { status: 401, statusText: 'Unauthorized' });
    });

    it('should clear auth state and return false when server responds with 403', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const token = createJwt({ sub: 'user1', exp: futureExp });
      service.saveToken(token);
      service.saveUsername('testuser');

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        expect(localStorage.getItem('token')).toBeNull();
        expect(localStorage.getItem('username')).toBeNull();
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      req.flush(null, { status: 403, statusText: 'Forbidden' });
    });

    it('should return false on network error', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const token = createJwt({ sub: 'user1', exp: futureExp });
      service.saveToken(token);

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      req.error(new ErrorEvent('Network error'));
    });

    it('should handle server 500 error gracefully (fail-closed)', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const token = createJwt({ sub: 'user1', exp: futureExp });
      service.saveToken(token);

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      req.flush(null, { status: 500, statusText: 'Internal Server Error' });
    });
  });

  describe('Integration: locally valid token that is server-invalid (revoked)', () => {
    it('should allow navigation locally but fail server validation on revoked token', (done) => {
      const futureExp = Math.floor(Date.now() / 1000) + 3600;
      const token = createJwt({ sub: 'user1', exp: futureExp });
      service.saveToken(token);

      expect(service.isLoggedInLocally()).toBe(true);

      service.validateTokenWithServer().subscribe((result) => {
        expect(result).toBe(false);
        expect(localStorage.getItem('token')).toBeNull();
        done();
      });

      const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
      req.flush(null, { status: 401, statusText: 'Unauthorized' });
    });
  });
});

function createJwt(payload: any): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payloadStr = btoa(JSON.stringify(payload));
  const signature = 'dummy_signature';
  return `${header}.${payloadStr}.${signature}`;
}
