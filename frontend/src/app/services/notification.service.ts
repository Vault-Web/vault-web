import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable, catchError, map, of, tap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface InboxNotification {
  id: number;
  source: 'SECURITY' | 'CHAT';
  type: string;
  title: string;
  message: string;
  linkUrl: string | null;
  referenceId: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPreference {
  source: 'SECURITY' | 'CHAT';
  muted: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly apiUrl = environment.mainApiUrl + '/notifications';
  private readonly unreadSubject = new BehaviorSubject<number>(0);
  readonly unreadCount$ = this.unreadSubject.asObservable();

  constructor(private readonly http: HttpClient) {}

  refreshUnreadCount(): Observable<number> {
    return this.http.get<{ count: number }>(`${this.apiUrl}/unread-count`).pipe(
      map((response) => response.count),
      tap((count) => this.unreadSubject.next(count)),
      catchError(() => of(this.unreadSubject.value)),
    );
  }

  list(source: string = '', unreadOnly = false): Observable<InboxNotification[]> {
    let params = new HttpParams().set('unreadOnly', unreadOnly).set('limit', 100);
    if (source) params = params.set('source', source);
    return this.http.get<InboxNotification[]>(this.apiUrl, { params });
  }

  markRead(id: number, read: boolean): Observable<InboxNotification> {
    return this.http.patch<InboxNotification>(`${this.apiUrl}/${id}/read`, { read }).pipe(
      tap(() => this.refreshUnreadCount().subscribe()),
    );
  }

  markAllRead(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>(`${this.apiUrl}/mark-all-read`, {}).pipe(
      tap(() => this.unreadSubject.next(0)),
    );
  }

  preferences(): Observable<NotificationPreference[]> {
    return this.http.get<NotificationPreference[]>(`${this.apiUrl}/preferences`);
  }

  setMuted(source: string, muted: boolean): Observable<NotificationPreference> {
    return this.http.put<NotificationPreference>(
      `${this.apiUrl}/preferences/${encodeURIComponent(source)}`,
      { muted },
    );
  }
}
