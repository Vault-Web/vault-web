import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import {
  InboxNotification,
  NotificationPreference,
  NotificationService,
} from '../../services/notification.service';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './notifications.component.html',
  styleUrl: './notifications.component.scss',
})
export class NotificationsComponent implements OnInit {
  items: InboxNotification[] = [];
  preferences: NotificationPreference[] = [];
  source = '';
  unreadOnly = false;
  loading = true;
  error = false;
  markingAll = false;

  constructor(private readonly notifications: NotificationService) {}

  ngOnInit(): void {
    this.load();
    this.loadPreferences();
  }

  load(): void {
    this.loading = true;
    this.error = false;
    this.notifications.list(this.source, this.unreadOnly).subscribe({
      next: (items) => {
        this.items = items;
        this.loading = false;
      },
      error: () => {
        this.error = true;
        this.loading = false;
      },
    });
    this.notifications.refreshUnreadCount().subscribe();
  }

  loadPreferences(): void {
    this.notifications.preferences().subscribe({
      next: (items) => {
        this.preferences = items;
      },
    });
  }

  markRead(item: InboxNotification, read: boolean): void {
    this.notifications.markRead(item.id, read).subscribe({
      next: (updated) => {
        this.items = this.items.map((current) =>
          current.id === updated.id ? updated : current,
        );
      },
      error: () => {
        this.error = true;
      },
    });
  }

  markAllRead(): void {
    this.markingAll = true;
    this.notifications.markAllRead().subscribe({
      next: () => {
        const now = new Date().toISOString();
        this.items = this.items.map((item) => ({
          ...item,
          readAt: item.readAt ?? now,
        }));
        this.markingAll = false;
      },
      error: () => {
        this.error = true;
        this.markingAll = false;
      },
    });
  }

  isMuted(source: string): boolean {
    return (
      this.preferences.find((item) => item.source === source)?.muted ?? false
    );
  }

  toggleMuted(source: string): void {
    const muted = !this.isMuted(source);
    this.notifications.setMuted(source, muted).subscribe({
      next: (preference) => {
        this.preferences = [
          ...this.preferences.filter((item) => item.source !== source),
          preference,
        ];
      },
      error: () => {
        this.error = true;
      },
    });
  }

  trackById(_index: number, item: InboxNotification): number {
    return item.id;
  }
}
