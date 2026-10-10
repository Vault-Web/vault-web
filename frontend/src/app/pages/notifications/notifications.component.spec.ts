import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { NotificationService } from '../../services/notification.service';
import { NotificationsComponent } from './notifications.component';

describe('NotificationsComponent', () => {
  let fixture: ComponentFixture<NotificationsComponent>;
  let component: NotificationsComponent;
  let service: jasmine.SpyObj<NotificationService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'list',
      'preferences',
      'refreshUnreadCount',
      'markRead',
      'markAllRead',
      'setMuted',
    ]);
    service.list.and.returnValue(of([]));
    service.preferences.and.returnValue(of([]));
    service.refreshUnreadCount.and.returnValue(of(0));
    service.markAllRead.and.returnValue(of({ updated: 0 }));

    await TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [{ provide: NotificationService, useValue: service }],
    }).compileComponents();

    fixture = TestBed.createComponent(NotificationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads notifications and source preferences', () => {
    expect(service.list).toHaveBeenCalled();
    expect(service.preferences).toHaveBeenCalled();
    expect(component.items).toEqual([]);
  });

  it('marks the visible inbox as read', () => {
    component.markAllRead();
    expect(service.markAllRead).toHaveBeenCalled();
    expect(component.markingAll).toBeFalse();
  });

  it('shows a recoverable error when inbox loading fails', () => {
    service.list.and.returnValue(throwError(() => new Error('offline')));
    component.load();
    expect(component.error).toBeTrue();
    expect(component.loading).toBeFalse();
  });
});
