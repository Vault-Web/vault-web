import {
  HttpErrorResponse,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError, ReplaySubject } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { NetworkStatusService } from '../services/network-status.service';
import { environment } from '../../../environments/environment';

const PUBLIC_API_PREFIXES = [`${environment.cloudServiceApiUrl}/public/`];

let refreshTokenSubject = new ReplaySubject<string>(1);
let isRefreshing = false;

export const tokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const networkStatusService = inject(NetworkStatusService);
  const router = inject(Router);

  // Public endpoints are opened by recipients without a session. Their 401 means
  // "wrong password", not "session expired", so it must never trigger a token
  // refresh or log the visitor out. The component renders these errors itself.
  if (isPublicEndpoint(req.url)) {
    return next(req).pipe(
      catchError((error: HttpErrorResponse) => {
        if (isBackendUnavailable(error)) {
          networkStatusService.showBackendUnavailable();
        }
        return throwError(() => error);
      }),
    );
  }

  // Explicit Authorization Header
  if (req.headers.has('Authorization')) {
    return next(req).pipe(
      catchError((error: HttpErrorResponse) => {
        if (isBackendUnavailable(error)) {
          networkStatusService.showBackendUnavailable();
          return throwError(() => error);
        }

        if (error.status === 401) {
          return handleUnauthorized(req, next, authService, error);
        }
        if (isServerError(error) && !isExcludedFromErrorPage(req.url)) {
          void router.navigate(['/error']);
        }
        return throwError(() => error);
      }),
    );
  }

  // Attach the token automatically if not present and not an auth endpoint.
  if (
    !req.url.includes('/login') &&
    !req.url.includes('/register') &&
    !req.url.includes('/refresh')
  ) {
    const token = authService.getToken();
    if (token) {
      req = req.clone({
        setHeaders: { Authorization: `Bearer ${token}` },
      });
    }
  }

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (isBackendUnavailable(error)) {
        networkStatusService.showBackendUnavailable();
        return throwError(() => error);
      }

      if (error.status !== 401) {
        if (isServerError(error) && !isExcludedFromErrorPage(req.url)) {
          void router.navigate(['/error']);
        }
        return throwError(() => error);
      }

      return handleUnauthorized(req, next, authService, error);
    }),
  );
};

// Consistently handle a 401 for any request: refresh the token once and retry,
// or clear the session and redirect to /login when the refresh fails. Requests
// that carried an explicit Authorization header used to fall through here without
// a refresh or logout, leaving the user on a broken, unauthenticated page.
function handleUnauthorized(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  authService: AuthService,
  error: HttpErrorResponse,
) {
  // If the refresh endpoint itself returned 401, the refresh token is invalid.
  if (req.url.includes('/auth/refresh')) {
    authService.logout();
    return throwError(() => error);
  }

  // A refresh is already in flight: wait for it and retry with the new token.
  if (isRefreshing) {
    return refreshTokenSubject.pipe(
      switchMap((token) =>
        next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })),
      ),
    );
  }

  isRefreshing = true;
  refreshTokenSubject = new ReplaySubject<string>(1);

  return authService.refresh().pipe(
    switchMap((res) => {
      isRefreshing = false;
      authService.saveToken(res.token);
      refreshTokenSubject.next(res.token);
      refreshTokenSubject.complete();

      return next(
        req.clone({ setHeaders: { Authorization: `Bearer ${res.token}` } }),
      );
    }),
    catchError((refreshErr) => {
      isRefreshing = false;
      refreshTokenSubject.error(refreshErr);
      authService.logout();
      return throwError(() => refreshErr);
    }),
  );
}

/**
 * The unauthenticated share endpoints, matched against the API bases rather than
 * on a bare '/public/' substring, so a future authenticated path that happens to
 * contain that segment is not silently stripped of its token.
 */
function isPublicEndpoint(url: string): boolean {
  return PUBLIC_API_PREFIXES.some((prefix) => url.startsWith(prefix));
}

function isBackendUnavailable(error: HttpErrorResponse): boolean {
  return error.status === 0;
}

function isServerError(error: HttpErrorResponse): boolean {
  return error.status >= 500;
}

// Exclude specific background endpoints (like refresh and devices) to avoid redirect loops
// or interrupting the user. We allow login/register 500s to redirect to the error page.
function isExcludedFromErrorPage(url: string): boolean {
  return (
    url.includes('/auth/refresh') ||
    url.includes('/auth/logout') ||
    url.includes('/devices')
  );
}
