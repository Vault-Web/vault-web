import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { finalize, map, Observable, tap, catchError } from 'rxjs';
import { of } from 'rxjs';
import { environment } from '../../environments/environment';
import { Router } from '@angular/router';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private apiUrl = environment.mainApiUrl;

  constructor(
    private http: HttpClient,
    private router: Router,
  ) {}

  login(username: string, password: string): Observable<{ token: string }> {
    return this.http
      .post<{
        token: string;
      }>(
        `${this.apiUrl}/auth/login`,
        { username, password },
        { withCredentials: true },
      )
      .pipe(
        tap((res) => {
          this.saveToken(res.token);
          this.saveUsername(username);
        }),
      );
  }

  register(username: string, password: string): Observable<string> {
    return this.http.post(
      `${this.apiUrl}/auth/register`,
      { username, password },
      { responseType: 'text' },
    );
  }

  refresh(): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(
      `${this.apiUrl}/auth/refresh`,
      {},
      { withCredentials: true },
    );
  }

  changePassword(
    currentPassword: string,
    newPassword: string,
  ): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/auth/change-password`, {
      currentPassword,
      newPassword,
    });
  }

  validateTokenWithServer(): Observable<boolean> {
    const token = this.getToken();
    if (!token) {
      return of(false);
    }

    return this.refresh().pipe(
      tap((res) => {
        this.saveToken(res.token);
      }),
      map(() => true),
      catchError((error: HttpErrorResponse) => {
        if (error.status === 401 || error.status === 403) {
          this.clearAuthState();
          return of(false);
        }
        return of(false);
      }),
    );
  }

  isLoggedInLocally(): boolean {
    const token = localStorage.getItem('token');
    if (!token) {
      return false;
    }
    const payload = this.decodeTokenPayload(token);
    // A token we cannot decode is treated as logged-out (fail closed), so a stale or
    // malformed token never grants navigation to protected pages. A valid token with no
    // `exp` claim never expires and stays logged in; otherwise it must not be past expiry.
    if (payload === null) {
      return false;
    }
    if (typeof payload.exp !== 'number') {
      return true;
    }
    return Date.now() < payload.exp * 1000;
  }

  isLoggedIn(): boolean {
    return this.isLoggedInLocally();
  }

  private clearAuthState(): void {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
  }

  saveToken(token: string): void {
    localStorage.setItem('token', token);
  }

  saveUsername(username: string): void {
    localStorage.setItem('username', username);
  }

  getToken(): string | null {
    return localStorage.getItem('token') as string | null;
  }

  getUsername(): string | null {
    return localStorage.getItem('username') as string | null;
  }

  private decodeTokenPayload(token: string): { exp?: number } | null {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return null;
    }
    try {
      return JSON.parse(this.decodeBase64Url(parts[1])) as { exp?: number };
    } catch {
      return null;
    }
  }

  // JWT payloads are base64url-encoded UTF-8; decode to bytes and run them through
  // TextDecoder so non-ASCII claims don't corrupt the parse.
  private decodeBase64Url(value: string): string {
    const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    return new TextDecoder('utf-8').decode(bytes);
  }

  logout(): void {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    this.http
      .post(`${this.apiUrl}/auth/logout`, {}, { withCredentials: true })
      .pipe(
        finalize(() => {
          this.router.navigate(['/login']);
        }),
      )
      .subscribe({
        error: (err) => {
          console.error('Backend logout failed', err);
        },
      });
  }

  checkUsernameExists(username: string): Observable<boolean> {
    return this.http
      .get<{
        exists: boolean;
      }>(`${this.apiUrl}/auth/check-username`, { params: { username } })
      .pipe(map((response) => response.exists));
  }
}
