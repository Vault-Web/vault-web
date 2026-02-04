import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { catchError, of } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private apiUrl = environment.mainApiUrl;

  constructor(private http: HttpClient) {}

  login(username: string, password: string): Observable<{ token: string }> {
    return this.http
      .post<{
        token: string;
        // }>(`${this.apiUrl}/auth/login`, { username, password })
      }>(`${this.apiUrl}user/login`, { username, password })
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

  validateToken(): Observable<boolean> {
    const token = sessionStorage.getItem('token');

    if (!token) {
      return of(false);
    }

    return this.http.get(`${this.apiUrl}/auth/validate`).pipe(
      map(() => true),
      catchError(() => of(false)),
    );
  }

  saveToken(token: string): void {
    sessionStorage.setItem('token', token);
  }

  saveUsername(username: string): void {
    sessionStorage.setItem('username', username);
  }

  getToken(): string | null {
    return sessionStorage.getItem('token');
  }

  getUsername(): string | null {
    return sessionStorage.getItem('username');
  }

  isLoggedIn(): boolean {
    return !!sessionStorage.getItem('token');
  }

  logout(): void {
    sessionStorage.removeItem('token');
    sessionStorage.removeItem('username');
  }

  checkUsernameExists(username: string): Observable<boolean> {
    return this.http
      .get<{
        exists: boolean;
      }>(`${this.apiUrl}/auth/check-username`, { params: { username } })
      .pipe(map((response) => response.exists));
  }
}
