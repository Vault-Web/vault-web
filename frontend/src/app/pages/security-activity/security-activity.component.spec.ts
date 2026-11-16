import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SecurityActivityComponent } from './security-activity.component';
import { SecurityEventDto, UserService } from '../../services/user.service';
import { UiToastService } from '../../core/services/ui-toast.service';

describe('SecurityActivityComponent', () => {
  let fixture: ComponentFixture<SecurityActivityComponent>;
  let component: SecurityActivityComponent;
  let userService: jasmine.SpyObj<UserService>;
  let toast: jasmine.SpyObj<UiToastService>;

  const event = (
    id: number,
    eventType = 'LOGIN',
    status = 'FAILURE',
  ): SecurityEventDto => ({
    id,
    eventType,
    status,
    timestamp: '2026-11-15T10:00:00Z',
    ipAddress: '192.0.2.1',
    userAgent: 'unknown',
    location: 'Unknown',
  });

  beforeEach(async () => {
    userService = jasmine.createSpyObj('UserService', ['getSecurityActivity']);
    toast = jasmine.createSpyObj('UiToastService', ['error']);
    userService.getSecurityActivity.and.returnValue(
      of([event(1), event(2, 'LOGIN', 'SUCCESS'), event(3, 'PASSWORD_CHANGE')]),
    );
    await TestBed.configureTestingModule({
      imports: [SecurityActivityComponent],
      providers: [
        provideRouter([]),
        { provide: UserService, useValue: userService },
        { provide: UiToastService, useValue: toast },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(SecurityActivityComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('combines native type and failure controls and clears them without losing events', async () => {
    const select: HTMLSelectElement =
      fixture.nativeElement.querySelector('select');
    const checkbox: HTMLInputElement =
      fixture.nativeElement.querySelector('input');
    select.value = 'LOGIN';
    select.dispatchEvent(new Event('change'));
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(component.filteredEvents.map((item) => item.id)).toEqual([1]);
    expect(
      fixture.nativeElement.querySelectorAll('.timeline-entry').length,
    ).toBe(1);
    expect(
      fixture.nativeElement.querySelector('.filter-count').textContent,
    ).toContain('Showing 1 of 3');
    expect(fixture.nativeElement.querySelector('.badge').textContent).toContain(
      '3 events',
    );
    fixture.nativeElement.querySelector('.clear-filters').click();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelectorAll('.timeline-entry').length,
    ).toBe(3);
    expect(component.events.length).toBe(3);
    expect(userService.getSecurityActivity).toHaveBeenCalledTimes(1);
  });

  it('distinguishes an empty filtered result from an account with no history', () => {
    component.selectedEventType = 'LOGOUT';
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'No events match these filters.',
    );
    expect(fixture.nativeElement.textContent).not.toContain(
      'No security activity logged yet.',
    );
    component.events = [];
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'No security activity logged yet.',
    );
    expect(fixture.nativeElement.querySelector('.activity-filters')).toBeNull();
  });

  it('detects consecutive failures from unfiltered history without creating a streak across other events', () => {
    component.events = [
      event(1),
      event(2),
      event(3),
      event(4, 'LOGIN', 'SUCCESS'),
    ];
    component.selectedEventType = 'PASSWORD_CHANGE';
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('.failed-login-hint').textContent,
    ).toContain('3 consecutive failed logins');
    for (const separator of [
      event(5, 'LOGIN', 'SUCCESS'),
      event(5, 'LOGOUT'),
    ]) {
      component.events = [event(1), event(2), separator, event(3), event(4)];
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('.failed-login-hint'),
      ).toBeNull();
    }
  });

  it('keeps unknown server event types filterable', () => {
    component.events = [event(1, 'FUTURE_EVENT'), event(2, 'LOGIN', 'SUCCESS')];
    component.selectedEventType = 'FUTURE_EVENT';
    fixture.detectChanges();
    expect(component.eventTypes).toEqual(['FUTURE_EVENT', 'LOGIN']);
    expect(
      fixture.nativeElement.querySelector('.event-name').textContent,
    ).toContain('FUTURE_EVENT');
  });

  it('retains the existing load error and retry flow', () => {
    userService.getSecurityActivity.and.returnValue(
      throwError(() => new Error('offline')),
    );
    component.retry();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('.error-state').textContent,
    ).toContain('Could not load');
    expect(fixture.nativeElement.querySelector('.activity-filters')).toBeNull();
    expect(toast.error).toHaveBeenCalled();
    userService.getSecurityActivity.and.returnValue(of([event(9)]));
    fixture.nativeElement.querySelector('.retry-btn').click();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelectorAll('.timeline-entry').length,
    ).toBe(1);
  });
});
