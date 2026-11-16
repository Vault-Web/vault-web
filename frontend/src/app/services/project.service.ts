import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { SKIP_GLOBAL_ERROR_PAGE } from '../core/interceptors/token.interceptor';

export type ProjectRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export interface ProjectResource {
  id: number;
  available: boolean;
  ownerUsername: string | null;
  path: string | null;
  folder: boolean | null;
  shareId: string | null;
}
export interface Project {
  id: number;
  name: string;
  description: string;
  archived: boolean;
  createdAt: string;
  role: ProjectRole;
  discussionGroupId: number | null;
  members: { userId: number; username: string; role: ProjectRole }[];
  resources: ProjectResource[];
  activity: { event: string; actor: string; createdAt: string }[];
}

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.mainApiUrl}/projects`;
  private readonly options = {
    context: new HttpContext().set(SKIP_GLOBAL_ERROR_PAGE, true),
  };

  list() {
    return this.http.get<Project[]>(this.url, this.options);
  }
  get(id: number) {
    return this.http.get<Project>(`${this.url}/${id}`, this.options);
  }
  create(name: string, description: string, discussionGroupId: number | null) {
    return this.http.post<{ id: number }>(
      this.url,
      { name, description, discussionGroupId },
      this.options,
    );
  }
  update(
    id: number,
    payload: {
      name?: string;
      description?: string;
      archived?: boolean;
      discussionGroupId?: number | null;
    },
  ) {
    return this.http.patch<void>(`${this.url}/${id}`, payload, this.options);
  }
  member(id: number, username: string, role: ProjectRole) {
    return this.http.put<void>(
      `${this.url}/${id}/members`,
      { username, role },
      this.options,
    );
  }
  removeMember(id: number, userId: number) {
    return this.http.delete<void>(
      `${this.url}/${id}/members/${userId}`,
      this.options,
    );
  }
  leave(id: number) {
    return this.http.delete<void>(`${this.url}/${id}/leave`, this.options);
  }
  delete(id: number) {
    return this.http.delete<void>(`${this.url}/${id}`, this.options);
  }
  link(id: number, path: string, folder: boolean) {
    return this.http.post<void>(
      `${this.url}/${id}/resources`,
      { path, folder },
      this.options,
    );
  }
  unlink(id: number, linkId: number) {
    return this.http.delete<void>(
      `${this.url}/${id}/resources/${linkId}`,
      this.options,
    );
  }
}
