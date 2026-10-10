import { CommonModule } from '@angular/common';
import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { RouterModule } from '@angular/router';
import { interval, Subscription } from 'rxjs';
import { ThemeService } from '../services/theme.service';
import { AuthService } from '../services/auth.service';
import { UserService } from '../services/user.service';
import { NotificationService } from '../services/notification.service';
import {
  EXTERNAL_DOMAIN_LINKS,
  ExternalDomainLink,
  resolveExternalLinkUrl,
} from '../config/external-domains.config';

@Component({
  selector: 'app-navbar',
  imports: [CommonModule, RouterModule],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.scss',
})
// implements OnInit — this tells Angular to call ngOnInit() after the component is created
export class NavbarComponent implements OnInit, OnDestroy {
  isMobileMenuOpen = false;
  isDomainDropdownOpen = false;
  readonly externalDomainLinks: ExternalDomainLink[] = EXTERNAL_DOMAIN_LINKS;

  // Stores the full URL to the profile picture (e.g. "http://localhost:8080/uploads/...")
  // null means no picture is set — the template will show the fallback initial instead
  profilePictureUrl: string | null = null;
  unreadNotificationCount = 0;
  private unreadSubscription?: Subscription;
  private pollingSubscription?: Subscription;

  constructor(
    public themeService: ThemeService,
    public authService: AuthService,
    private userService: UserService,
    private notificationService: NotificationService,
  ) {}

  /**
   * Loads the profile picture when the navbar initializes.
   */
  ngOnInit(): void {
    if (this.authService.isLoggedIn()) {
      this.notificationService.refreshUnreadCount().subscribe();
      this.unreadSubscription = this.notificationService.unreadCount$.subscribe(
        (count) => {
          this.unreadNotificationCount = count;
        },
      );
      this.pollingSubscription = interval(30000).subscribe(() => {
        if (this.authService.isLoggedIn()) {
          this.notificationService.refreshUnreadCount().subscribe();
        }
      });
      // Subscribe to reactive profile picture updates
      this.userService.profilePicUrl$.subscribe((url) => {
        this.profilePictureUrl = url;
      });
      // Fetch initial picture
      this.userService.getProfilePicture().subscribe({
        error: () => {
          this.profilePictureUrl = null;
        },
      });
    }
  }

  ngOnDestroy(): void {
    this.unreadSubscription?.unsubscribe();
    this.pollingSubscription?.unsubscribe();
  }

  /**
   * Returns the first letter of the username, uppercased.
   * Used as the fallback avatar text when no profile picture is set.
   * Example: username "alice" → returns "A"
   */
  get usernameInitial(): string {
    const username = this.authService.getUsername();
    return username ? username.charAt(0).toUpperCase() : '?';
  }

  @HostListener('document:click')
  closeDomainDropdownOnOutsideClick(): void {
    this.isDomainDropdownOpen = false;
  }

  toggleMobileMenu() {
    this.closeDomainDropdown();
    this.isMobileMenuOpen = !this.isMobileMenuOpen;
  }

  closeMobileMenu() {
    this.isMobileMenuOpen = false;
  }

  toggleTheme() {
    this.themeService.toggleTheme();
  }

  toggleDomainDropdown(event: Event): void {
    if (!this.hasExternalLinks) {
      return;
    }
    event.stopPropagation();
    this.isDomainDropdownOpen = !this.isDomainDropdownOpen;
  }

  closeDomainDropdown(event?: Event): void {
    event?.stopPropagation();
    this.isDomainDropdownOpen = false;
  }

  get isDarkTheme(): boolean {
    return this.themeService.getCurrentTheme() === 'dark';
  }

  get hasExternalLinks(): boolean {
    return this.externalDomainLinks.length > 0;
  }

  externalLinkUrl(link: ExternalDomainLink): string {
    return resolveExternalLinkUrl(link, this.authService.getToken());
  }

  openExternalLink(event: Event, link: ExternalDomainLink): void {
    this.closeMobileMenu();
    this.closeDomainDropdown(event);

    if (!link.forwardVaultWebToken || !this.authService.getToken()) {
      return;
    }

    event.preventDefault();
    this.authService.refresh().subscribe({
      next: (res) => {
        this.authService.saveToken(res.token);
        window.location.assign(resolveExternalLinkUrl(link, res.token));
      },
      error: () => {
        window.location.assign(this.externalLinkUrl(link));
      },
    });
  }

  logout(): void {
    this.closeMobileMenu();
    this.closeDomainDropdown();
    this.authService.logout();
  }
}
