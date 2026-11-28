import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { UserService, SecurityEventDto } from '../../services/user.service';
import { UiToastService } from '../../core/services/ui-toast.service';

@Component({
  selector: 'app-security-activity',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './security-activity.component.html',
  styleUrl: './security-activity.component.scss',
})
export class SecurityActivityComponent implements OnInit {
  events: SecurityEventDto[] = [];
  isLoading = true;
  error: string | null = null;
  selectedEventType = '';
  failuresOnly = false;

  get eventTypes(): string[] {
    return [...new Set(this.events.map((event) => event.eventType))].sort();
  }

  get filteredEvents(): SecurityEventDto[] {
    return this.events.filter(
      (event) =>
        (!this.selectedEventType ||
          event.eventType === this.selectedEventType) &&
        (!this.failuresOnly || event.status === 'FAILURE'),
    );
  }

  get repeatedFailedLogins(): number {
    let consecutive = 0;
    let longest = 0;
    for (const event of this.events) {
      consecutive =
        event.eventType === 'LOGIN' && event.status === 'FAILURE'
          ? consecutive + 1
          : 0;
      longest = Math.max(longest, consecutive);
    }
    return longest >= 3 ? longest : 0;
  }

  clearFilters(): void {
    this.selectedEventType = '';
    this.failuresOnly = false;
  }

  constructor(
    private userService: UserService,
    private toast: UiToastService,
  ) {}

  ngOnInit(): void {
    this.loadSecurityActivity();
  }

  loadSecurityActivity(): void {
    this.isLoading = true;
    this.error = null;
    this.userService.getSecurityActivity().subscribe({
      next: (data) => {
        this.events = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load security activity', err);
        this.error = 'Could not load your security events. Please try again.';
        this.isLoading = false;
        this.toast.error(
          'Load failed',
          'Failed to retrieve security activity.',
        );
      },
    });
  }

  retry(): void {
    this.loadSecurityActivity();
  }

  getFriendlyEventType(type: string): string {
    switch (type) {
      case 'LOGIN':
        return 'Account Login';
      case 'LOGOUT':
        return 'Account Logout';
      case 'REGISTER':
        return 'Account Registration';
      case 'TOKEN_REFRESH':
        return 'Session Refreshed';
      case 'PASSWORD_CHANGE':
        return 'Password Changed';
      case 'NEW_DEVICE_DETECTED':
        return 'New Device Detected';
      case 'VAULT_UNLOCKED':
        return 'Vault Unlocked';
      case 'VAULT_LOCKED':
        return 'Vault Locked';
      case 'PAT_CREATED':
        return 'Access Token Created';
      case 'PAT_REVOKED':
        return 'Access Token Revoked';
      case 'PAT_REVOKED_ALL':
        return 'All Access Tokens Revoked';
      default:
        return type;
    }
  }

  getEventIcon(type: string): string {
    switch (type) {
      case 'LOGIN':
        return 'pi-sign-in';
      case 'LOGOUT':
        return 'pi-sign-out';
      case 'REGISTER':
        return 'pi-user-plus';
      case 'TOKEN_REFRESH':
        return 'pi-refresh';
      case 'PASSWORD_CHANGE':
        return 'pi-key';
      case 'NEW_DEVICE_DETECTED':
        return 'pi-desktop';
      case 'VAULT_UNLOCKED':
        return 'pi-lock-open';
      case 'VAULT_LOCKED':
        return 'pi-lock';
      case 'PAT_CREATED':
        return 'pi-key';
      case 'PAT_REVOKED':
      case 'PAT_REVOKED_ALL':
        return 'pi-ban';
      default:
        return 'pi-shield';
    }
  }

  isHighlightEvent(type: string): boolean {
    return type === 'NEW_DEVICE_DETECTED' || type === 'LOGIN';
  }

  getFriendlyDevice(userAgent: string): string {
    if (!userAgent || userAgent === 'unknown') return 'Unknown Device';

    let os = 'Unknown OS';
    if (userAgent.indexOf('Windows') !== -1) os = 'Windows';
    else if (
      userAgent.indexOf('Macintosh') !== -1 ||
      userAgent.indexOf('Mac OS') !== -1
    )
      os = 'macOS';
    else if (userAgent.indexOf('Android') !== -1) os = 'Android';
    else if (
      userAgent.indexOf('iPhone') !== -1 ||
      userAgent.indexOf('iPad') !== -1
    )
      os = 'iOS';
    else if (userAgent.indexOf('Linux') !== -1) os = 'Linux';

    let browser = 'Unknown Browser';
    if (
      userAgent.indexOf('Chrome') !== -1 &&
      userAgent.indexOf('Chromium') === -1 &&
      userAgent.indexOf('Edg') === -1
    )
      browser = 'Chrome';
    else if (
      userAgent.indexOf('Safari') !== -1 &&
      userAgent.indexOf('Chrome') === -1
    )
      browser = 'Safari';
    else if (userAgent.indexOf('Firefox') !== -1) browser = 'Firefox';
    else if (userAgent.indexOf('Edg') !== -1) browser = 'Edge';
    else if (
      userAgent.indexOf('Opera') !== -1 ||
      userAgent.indexOf('OPR') !== -1
    )
      browser = 'Opera';

    if (os === 'Unknown OS' && browser === 'Unknown Browser') {
      return userAgent.length > 50
        ? userAgent.substring(0, 50) + '...'
        : userAgent;
    }
    return `${browser} on ${os}`;
  }

  trackById(_index: number, item: SecurityEventDto): number {
    return item.id;
  }
}
