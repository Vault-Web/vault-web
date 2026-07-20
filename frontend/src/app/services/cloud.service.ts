import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { FolderDto } from '../models/dtos/FolderDto';
import { FolderContentItemDto } from '../models/dtos/FolderContentItemDto';
import { PageResponseDto } from '../models/dtos/PageResponseDto';
import { TrashEntryDto } from '../models/dtos/TrashEntryDto';
import { SearchResultDto } from '../models/dtos/SearchResultDto';
import { ScanJobDto } from '../models/dtos/ScanJobDto';
import { StorageQuotaDto } from '../models/dtos/StorageQuotaDto';
import { environment } from '../../environments/environment';

interface RawQuotaResponse {
  usedBytes?: number;
  used?: number;
  usedSpace?: number;
  totalBytes?: number;
  quotaBytes?: number;
  total?: number;
  totalSpace?: number;
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
  ): Observable<PageResponseDto<FolderContentItemDto>> {
    const path = this.normalizePath(relativePath);
    let params = new HttpParams()
      .set('path', path)
      .set('page', String(page))
      .set('size', String(size));
    if (sort) {
      params = params.set('sort', sort);
    }
    return this.http.get<PageResponseDto<FolderContentItemDto>>(
      `${this.apiUrl}/folders/content`,
      { params },
    );
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

  getStorageQuota(): Observable<StorageQuotaDto> {
    return this.http.get<RawQuotaResponse>(`${this.apiUrl}/storage/quota`).pipe(
      map((res) => ({
        usedBytes: Number(res?.usedBytes ?? res?.used ?? res?.usedSpace ?? 0),
        totalBytes: Number(
          res?.totalBytes ??
            res?.quotaBytes ??
            res?.total ??
            res?.totalSpace ??
            10 * 1024 * 1024 * 1024,
        ),
      })),
    );
  }
}
