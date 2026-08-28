import {
  HttpClient,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { FolderContentItemDto } from '../models/dtos/FolderContentItemDto';
import { PublicShareDto } from '../models/dtos/SecureSendLinkDto';

/** Header the backend reads the share password from. */
export const SECURE_SEND_PASSWORD_HEADER = 'X-Secure-Send-Password';

/**
 * Talks to the endpoints a share recipient may call without an account. Kept apart from
 * CloudService so the public landing page pulls in no authenticated state.
 *
 * <p>A folder link is browsed through these same calls: `path` is always relative to the shared
 * resource, and the backend confines it there. The password travels per request in a header —
 * there is no session for an anonymous recipient to hold.
 */
@Injectable({ providedIn: 'root' })
export class PublicShareService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.cloudServiceApiUrl}/public/secure-sends`;

  describe(token: string): Observable<PublicShareDto> {
    return this.http.get<PublicShareDto>(
      `${this.baseUrl}/${encodeURIComponent(token)}/meta`,
    );
  }

  /** Lists one level of a folder link. `path` is '' for the shared folder itself. */
  content(
    token: string,
    path = '',
    password?: string,
  ): Observable<FolderContentItemDto[]> {
    return this.http.get<FolderContentItemDto[]>(
      `${this.baseUrl}/${encodeURIComponent(token)}/content`,
      { headers: this.passwordHeader(password), params: this.pathParam(path) },
    );
  }

  /**
   * Direct URL of a download, for letting the browser fetch it itself. Only usable
   * on a link without a password: the password travels in a header, which a plain
   * navigation cannot carry, and putting it in the query string would write it to
   * every access log on the way.
   */
  downloadUrl(token: string, path = '', folder = false): string {
    const suffix = folder ? '/download-folder' : '';
    return (
      `${this.baseUrl}/${encodeURIComponent(token)}${suffix}` +
      `?path=${encodeURIComponent(path)}`
    );
  }

  download(
    token: string,
    password?: string,
    path = '',
  ): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/${encodeURIComponent(token)}`, {
      headers: this.passwordHeader(password),
      params: this.pathParam(path),
      responseType: 'blob',
      observe: 'response',
    });
  }

  /** Fetches a file for previewing it in place, rather than as an attachment. */
  view(token: string, path = '', password?: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${encodeURIComponent(token)}/view`, {
      headers: this.passwordHeader(password),
      params: this.pathParam(path),
      responseType: 'blob',
    });
  }

  /** Downloads a folder link, or one folder inside it, as a ZIP archive. */
  downloadFolder(
    token: string,
    path = '',
    password?: string,
  ): Observable<HttpResponse<Blob>> {
    return this.http.get(
      `${this.baseUrl}/${encodeURIComponent(token)}/download-folder`,
      {
        headers: this.passwordHeader(password),
        params: this.pathParam(path),
        responseType: 'blob',
        observe: 'response',
      },
    );
  }

  private passwordHeader(password?: string): HttpHeaders | undefined {
    return password
      ? new HttpHeaders({ [SECURE_SEND_PASSWORD_HEADER]: password })
      : undefined;
  }

  private pathParam(path: string): HttpParams {
    return new HttpParams().set('path', path);
  }
}
