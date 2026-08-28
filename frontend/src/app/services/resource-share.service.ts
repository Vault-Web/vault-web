import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { FolderContentItemDto } from '../models/dtos/FolderContentItemDto';
import {
  CreateResourceShareRequestDto,
  ResourceShareDto,
  SharePermission,
} from '../models/dtos/ResourceShareDto';

/**
 * Shares between Vault Web users. The shared resource stays in the owner's storage; the recipient
 * reaches it only through these endpoints, which enforce the granted permissions server side.
 */
@Injectable({ providedIn: 'root' })
export class ResourceShareService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.cloudServiceApiUrl}/shares`;

  create(
    path: string,
    recipientUsername: string,
    permissions: SharePermission[],
  ): Observable<ResourceShareDto> {
    const payload: CreateResourceShareRequestDto = {
      path,
      recipientUsername,
      permissions,
    };
    return this.http.post<ResourceShareDto>(this.baseUrl, payload);
  }

  /** Shares this user handed out. */
  listOwned(): Observable<ResourceShareDto[]> {
    return this.http.get<ResourceShareDto[]>(this.baseUrl);
  }

  /** Shares other users granted to this user. */
  listReceived(): Observable<ResourceShareDto[]> {
    return this.http.get<ResourceShareDto[]>(`${this.baseUrl}/shared-with-me`);
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(id)}`);
  }

  /** Recipient drops a share given to them (own access only; owner data kept). */
  leave(id: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/leave`,
    );
  }

  /** Lists a folder share, optionally a sub-path inside it. */
  listContent(id: string, path = ''): Observable<FolderContentItemDto[]> {
    return this.http.get<FolderContentItemDto[]>(
      `${this.baseUrl}/${encodeURIComponent(id)}/content`,
      { params: new HttpParams().set('path', path) },
    );
  }

  /**
   * Reads a file inside a share for preview or editing. Requires only VIEW, so a
   * recipient without DOWNLOAD can still open what was shared with them.
   */
  viewFile(id: string, path = ''): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${encodeURIComponent(id)}/view`, {
      params: new HttpParams().set('path', path),
      responseType: 'blob',
    });
  }

  downloadFile(id: string, path = ''): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${encodeURIComponent(id)}/download`, {
      params: new HttpParams().set('path', path),
      responseType: 'blob',
    });
  }

  downloadFolder(id: string, path = ''): Observable<Blob> {
    return this.http.get(
      `${this.baseUrl}/${encodeURIComponent(id)}/download-folder`,
      { params: new HttpParams().set('path', path), responseType: 'blob' },
    );
  }

  /** Overwrites a file inside a share. Requires the EDIT permission. */
  editFile(id: string, path: string, file: File): Observable<void> {
    const form = new FormData();
    form.append('file', file);
    return this.http.put<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/edit`,
      form,
      { params: new HttpParams().set('path', path) },
    );
  }

  /** Adds a new file to a folder share. Requires EDIT. `folderPath` is the sub-folder, '' for root. */
  upload(id: string, folderPath: string, file: File): Observable<void> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/upload`,
      form,
      { params: new HttpParams().set('path', folderPath) },
    );
  }

  /** Creates a sub-folder inside a folder share. Requires EDIT. */
  createFolder(id: string, parentPath: string, name: string): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/folder`,
      null,
      {
        params: new HttpParams().set('path', parentPath).set('name', name),
      },
    );
  }

  /** Deletes a file or folder inside a share. Requires EDIT. */
  deleteEntry(id: string, path: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/entry`,
      { params: new HttpParams().set('path', path) },
    );
  }

  /** Renames a file or folder inside a share (same parent). Requires EDIT. */
  rename(id: string, path: string, newName: string): Observable<void> {
    return this.http.patch<void>(
      `${this.baseUrl}/${encodeURIComponent(id)}/rename`,
      null,
      { params: new HttpParams().set('path', path).set('newName', newName) },
    );
  }
}
