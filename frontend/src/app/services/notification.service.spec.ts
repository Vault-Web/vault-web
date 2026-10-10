import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NotificationService } from './notification.service';
import { environment } from '../../environments/environment';

describe('NotificationService', () => {
  let service: NotificationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(NotificationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('updates unread count from API response', () => {
    service.refreshUnreadCount().subscribe((count) => expect(count).toBe(2));
    http.expectOne(`${environment.mainApiUrl}/notifications/unread-count`)
      .flush({ count: 2 });
  });

  it('sends mark-read state for one notification', () => {
    service.markRead(9, true).subscribe();
    http.expectOne(`${environment.mainApiUrl}/notifications/9/read`)
      .flush({ id: 9 });
    http.expectOne(`${environment.mainApiUrl}/notifications/unread-count`)
      .flush({ count: 0 });
  });
});
