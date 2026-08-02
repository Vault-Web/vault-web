import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { FolderDto } from '../models/dtos/FolderDto';
import { FolderContentItemDto } from '../models/dtos/FolderContentItemDto';
import { PageResponseDto } from '../models/dtos/PageResponseDto';
import { TrashEntryDto } from '../models/dtos/TrashEntryDto';
import { SearchResultDto } from '../models/dtos/SearchResultDto';
import { ScanJobDto } from '../models/dtos/ScanJobDto';
import { FileChecksumDto } from '../models/dtos/FileChecksumDto';
import {
  SecureSendLinkDto,
  CreateSecureSendRequestDto,
} from '../models/dtos/SecureSendLinkDto';
import { environment } from '../../environments/environment';

interface RawSecureSendResponse {
  id?: string;
  linkId?: string;
  token?: string;
  shareToken?: string;
  filePath?: string;
  path?: string;
  fileName?: string;
  name?: string;
  url?: string;
  shareUrl?: string;
  expiresAt?: string;
  passwordProtected?: boolean;
  hasPassword?: boolean;
  protected?: boolean;
  revoked?: boolean;
  isRevoked?: boolean;
  revokedAt?: string | null;
  createdAt?: string;
}

@Injectable({
  providedIn: 'root',
})
export class CloudService {
  public apiUrl = environment.cloudServiceApiUrl;

  constructor(private http: HttpClient) {}

  private normalizePath(path?: string): string {
    if (!path || path.trim() === '' || path === '/') return '/';
    return path;
  }

  getRootFolder(includeChildCounts = false): Observable<FolderDto> {
    const params = new HttpParams().set(
      'includeChildCounts',
      String(includeChildCounts),
    );
    return this.http.get<FolderDto>(`${this.apiUrl}/folders`, { params });
  }

  getFolderByPath(
    relativePath?: string,
    includeChildCounts = false,
  ): Observable<FolderDto> {
    const path = this.normalizePath(relativePath);
    const params = new HttpParams()
      .set('path', path)
      .set('includeChildCounts', String(includeChildCounts));
    return this.http.get<FolderDto>(`${this.apiUrl}/folders/path`, { params });
  }

  getFolderContent(
    relativePath?: string,
    page = 0,
    size = 50,
    sort?: string,
    includeDirectorySizes = true,
  ): Observable<PageResponseDto<FolderContentItemDto>> {
    const path = this.normalizePath(relativePath);
    let params = new HttpParams()
      .set('path', path)
      .set('page', String(page))
      .set('size', String(size))
      .set('includeDirectorySizes', String(includeDirectorySizes));
    if (sort) {
      params = params.set('sort', sort);
    }
    return this.http.get<PageResponseDto<FolderContentItemDto>>(
      `${this.apiUrl}/folders/content`,
      { params },
    );
  }

  getFolderSize(relativePath?: string): Observable<number> {
    const params = new HttpParams().set(
      'path',
      this.normalizePath(relativePath),
    );
    return this.http.get<number>(`${this.apiUrl}/folders/size`, { params });
  }

  searchInFolder(
    folderPath: string,
    query: string,
    maxResults = 20,
  ): Observable<SearchResultDto[]> {
    const params = new HttpParams()
      .set('folderPath', this.normalizePath(folderPath))
      .set('query', query)
      .set('maxResults', String(maxResults));
    return this.http.get<SearchResultDto[]>(`${this.apiUrl}/folders/search`, {
      params,
    });
  }

  getFileContent(relativePath: string): Observable<string> {
    const path = this.normalizePath(relativePath);
    return this.http.get(`${this.apiUrl}/files/content`, {
      params: new HttpParams().set('path', path),
      responseType: 'text',
    });
  }

  uploadFile(folderPath: string, file: File): Observable<FolderDto> {
    const formData = new FormData();
    formData.append('file', file);
    const path = this.normalizePath(folderPath);
    const params = new HttpParams().set('folderPath', path);

    return this.http.post<FolderDto>(`${this.apiUrl}/files/upload`, formData, {
      params,
    });
  }

  deleteFile(filePath: string): Observable<FolderDto> {
    const path = this.normalizePath(filePath);
    const params = new HttpParams().set('filePath', path);
    return this.http.delete<FolderDto>(`${this.apiUrl}/files`, { params });
  }

  renameOrMoveFile(filePath: string, newPath: string): Observable<FolderDto> {
    const params = new HttpParams()
      .set('filePath', this.normalizePath(filePath))
      .set('newPath', this.normalizePath(newPath));
    return this.http.patch<FolderDto>(`${this.apiUrl}/files/move`, null, {
      params,
    });
  }

  getFileBlob(relativePath: string): Observable<Blob> {
    const path = this.normalizePath(relativePath);
    return this.http.get(`${this.apiUrl}/files/download`, {
      params: new HttpParams().set('path', path),
      responseType: 'blob',
    });
  }

  createFolder(parentPath: string, name: string): Observable<FolderDto> {
    const params = new HttpParams()
      .set('parentPath', this.normalizePath(parentPath))
      .set('name', name);
    return this.http.post<FolderDto>(`${this.apiUrl}/folders`, null, {
      params,
    });
  }

  deleteFolder(folderPath: string): Observable<FolderDto> {
    const params = new HttpParams().set(
      'folderPath',
      this.normalizePath(folderPath),
    );
    return this.http.delete<FolderDto>(`${this.apiUrl}/folders`, { params });
  }

  renameOrMoveFolder(
    folderPath: string,
    newPath: string,
  ): Observable<FolderDto> {
    const params = new HttpParams()
      .set('folderPath', this.normalizePath(folderPath))
      .set('newPath', this.normalizePath(newPath));
    return this.http.patch<FolderDto>(`${this.apiUrl}/folders`, null, {
      params,
    });
  }

  getFolderArchive(relativePath: string): Observable<Blob> {
    const path = this.normalizePath(relativePath);
    return this.http.get(`${this.apiUrl}/folders/download`, {
      params: new HttpParams().set('path', path),
      responseType: 'blob',
    });
  }

  getFileView(path: string) {
    return this.http.get(`${this.apiUrl}/files/view`, {
      params: { path },
      responseType: 'blob',
    });
  }

  listTrash(): Observable<TrashEntryDto[]> {
    return this.http.get<TrashEntryDto[]>(`${this.apiUrl}/files/trash`);
  }

  restoreTrashEntry(id: string): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/files/trash/${encodeURIComponent(id)}/restore`,
      null,
    );
  }

  purgeTrashEntry(id: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/files/trash/${encodeURIComponent(id)}`,
    );
  }

  startFolderScan(relativePath?: string): Observable<ScanJobDto> {
    // The backend treats a blank path as "scan the whole root", so the folder
    // root ('/') is sent as an empty value rather than a literal slash.
    const path =
      !relativePath || relativePath === '/' ? '' : relativePath.trim();
    const params = new HttpParams().set('path', path);
    return this.http.post<ScanJobDto>(`${this.apiUrl}/files/scan`, null, {
      params,
    });
  }

  getScanJob(jobId: string): Observable<ScanJobDto> {
    return this.http.get<ScanJobDto>(
      `${this.apiUrl}/files/scan/${encodeURIComponent(jobId)}`,
    );
  }

  getFileChecksum(filePath: string): Observable<FileChecksumDto> {
    const normPath = this.normalizePath(filePath);
    const params = new HttpParams().set('path', normPath);
    return this.http
      .get<unknown>(`${this.apiUrl}/files/checksum`, { params })
      .pipe(
        map((res) => {
          const raw = res as
            | {
                checksum?: string;
                hash?: string;
                sha256?: string;
                value?: string;
                algorithm?: string;
              }
            | string;
          const checksum =
            typeof raw === 'string'
              ? raw
              : raw?.checksum || raw?.hash || raw?.sha256 || raw?.value || '';
          const algorithm =
            (typeof raw === 'object' && raw?.algorithm) || 'SHA-256';
          return {
            filePath: normPath,
            checksum,
            algorithm,
          };
        }),
      );
  }

  createSecureSendLink(
    filePath: string,
    expiryMinutes = 1440,
    password?: string,
  ): Observable<SecureSendLinkDto> {
    const normPath = this.normalizePath(filePath);
    const expiresAt = new Date(
      Date.now() + expiryMinutes * 60_000,
    ).toISOString();
    const payload: CreateSecureSendRequestDto = {
      filePath: normPath,
      expiresAt,
      password: password && password.trim() ? password.trim() : undefined,
    };

    return this.http
      .post<RawSecureSendResponse>(`${this.apiUrl}/secure-sends`, payload)
      .pipe(map((res) => this.mapSecureSendLink(res, normPath, password)));
  }

  listSecureSendLinks(): Observable<SecureSendLinkDto[]> {
    return this.http
      .get<RawSecureSendResponse[]>(`${this.apiUrl}/secure-sends`)
      .pipe(
        map((resList) =>
          Array.isArray(resList)
            ? resList.map((item) => this.mapSecureSendLink(item))
            : [],
        ),
      );
  }

  revokeSecureSendLink(id: string): Observable<void> {
    const encodedId = encodeURIComponent(id);
    return this.http.delete<void>(`${this.apiUrl}/secure-sends/${encodedId}`);
  }

  private mapSecureSendLink(
    res: RawSecureSendResponse,
    defaultPath = '',
    password?: string,
  ): SecureSendLinkDto {
    const rawPath = res?.filePath || res?.path || defaultPath;
    const fileName =
      res?.fileName ||
      res?.name ||
      (rawPath ? rawPath.substring(rawPath.lastIndexOf('/') + 1) : 'File');
    const id = res?.id || res?.linkId || res?.token || '';
    const token = res?.token || res?.shareToken || id;

    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const shareUrl =
      res?.url || res?.shareUrl || (token ? `${origin}/share/${token}` : '');

    return {
      id,
      filePath: rawPath,
      fileName,
      shareUrl,
      token,
      expiresAt: res?.expiresAt || '',
      hasPassword: Boolean(
        res?.passwordProtected ??
        res?.hasPassword ??
        res?.protected ??
        !!password,
      ),
      isRevoked: Boolean(res?.revoked ?? res?.isRevoked ?? false),
      revokedAt: res?.revokedAt || null,
      createdAt: res?.createdAt || '',
    };
  }
}
