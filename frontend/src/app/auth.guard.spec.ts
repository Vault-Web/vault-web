import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { authGuard } from './auth.guard';
import { AuthService } from './services/auth.service';
import { environment } from '../environments/environment';

describe('authGuard', () => {
  let httpMock: HttpTestingController;
  let authService: AuthService;
  let router: Router;

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
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('should deny access when no token exists', async () => {
    const result = await TestBed.runInInjectionContext(() =>
      authGuard({} as any, {} as any),
    );
    expect(result).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('should deny access when local token is expired', async () => {
    const pastExp = Math.floor(Date.now() / 1000) - 3600;
    const token = createJwt({ sub: 'user1', exp: pastExp });
    authService.saveToken(token);

    const result = await TestBed.runInInjectionContext(() =>
      authGuard({} as any, {} as any),
    );
    expect(result).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('should allow access when local token is valid and server refresh succeeds', async () => {
    const futureExp = Math.floor(Date.now() / 1000) + 3600;
    const oldToken = createJwt({ sub: 'user1', exp: futureExp });
    const newToken = createJwt({ sub: 'user1', exp: futureExp + 7200 });
    authService.saveToken(oldToken);

    const guardPromise = TestBed.runInInjectionContext(() =>
      authGuard({} as any, {} as any),
    );

    const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
    expect(req.request.method).toBe('POST');
    req.flush({ token: newToken });

    const result = await guardPromise;
    expect(result).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('should deny access when local token is valid but server rejects refresh (401)', async () => {
    const futureExp = Math.floor(Date.now() / 1000) + 3600;
    const token = createJwt({ sub: 'user1', exp: futureExp });
    authService.saveToken(token);

    const guardPromise = TestBed.runInInjectionContext(() =>
      authGuard({} as any, {} as any),
    );

    const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
    req.flush(null, { status: 401, statusText: 'Unauthorized' });

    const result = await guardPromise;
    expect(result).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
    expect(localStorage.getItem('token')).toBeNull();
  });

  it('should deny access on server error and redirect to login', async () => {
    const futureExp = Math.floor(Date.now() / 1000) + 3600;
    const token = createJwt({ sub: 'user1', exp: futureExp });
    authService.saveToken(token);

    const guardPromise = TestBed.runInInjectionContext(() =>
      authGuard({} as any, {} as any),
    );

    const req = httpMock.expectOne(`${environment.mainApiUrl}/auth/refresh`);
    req.error(new ErrorEvent('Network error'));

    const result = await guardPromise;
    expect(result).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});

function createJwt(payload: any): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payloadStr = btoa(JSON.stringify(payload));
  const signature = 'dummy_signature';
  return `${header}.${payloadStr}.${signature}`;
}
