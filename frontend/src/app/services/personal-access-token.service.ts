import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { CreatePersonalAccessTokenRequest } from '../models/dtos/CreatePersonalAccessTokenRequest';
import { CreatedPersonalAccessTokenDto } from '../models/dtos/CreatedPersonalAccessTokenDto';
import { PatScopeDto } from '../models/dtos/PatScopeDto';
import { PersonalAccessTokenDto } from '../models/dtos/PersonalAccessTokenDto';

/** Lifecycle API for personal access tokens (requires an interactive session). */
@Injectable({
  providedIn: 'root',
})
export class PersonalAccessTokenService {
  private baseUrl = `${environment.mainApiUrl}/auth/tokens`;

  constructor(private http: HttpClient) {}

  listScopes(): Observable<PatScopeDto[]> {
    return this.http.get<PatScopeDto[]>(`${this.baseUrl}/scopes`);
  }

  list(): Observable<PersonalAccessTokenDto[]> {
    return this.http.get<PersonalAccessTokenDto[]>(this.baseUrl);
  }

  create(
    request: CreatePersonalAccessTokenRequest,
  ): Observable<CreatedPersonalAccessTokenDto> {
    return this.http.post<CreatedPersonalAccessTokenDto>(this.baseUrl, request);
  }

  revoke(publicId: string): Observable<PersonalAccessTokenDto> {
    return this.http.delete<PersonalAccessTokenDto>(
      `${this.baseUrl}/${encodeURIComponent(publicId)}`,
    );
  }
}
