import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConfirmationService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';
import { UiToastService } from '../../core/services/ui-toast.service';
import { PatScopeDto } from '../../models/dtos/PatScopeDto';
import { PersonalAccessTokenDto } from '../../models/dtos/PersonalAccessTokenDto';
import { PersonalAccessTokenService } from '../../services/personal-access-token.service';

/**
 * Personal access tokens for CLI and automation clients: create named, scoped,
 * expiring tokens (secret shown once), list their metadata and revoke them.
 * The secret is only ever held in component memory while the reveal dialog is
 * open; it is never stored, logged or put in a URL.
 */
@Component({
  selector: 'app-access-tokens',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TableModule,
    ButtonModule,
    ConfirmDialogModule,
    DialogModule,
    TooltipModule,
  ],
  providers: [ConfirmationService],
  templateUrl: './access-tokens.component.html',
  styleUrls: ['./access-tokens.component.scss'],
})
export class AccessTokensComponent implements OnInit, OnDestroy {
  readonly expiryOptions = [7, 30, 90, 365];
  readonly maxNameLength = 64;

  tokens: PersonalAccessTokenDto[] = [];
  scopes: PatScopeDto[] = [];
  loading = true;
  error?: string;
  processingIds = new Set<string>();

  showCreateDialog = false;
  creating = false;
  createError?: string;
  name = '';
  selectedScopes = new Set<string>();
  expiresInDays = 30;
  currentPassword = '';
  showPassword = false;

  /** Raw secret of the token just created; cleared as soon as the dialog closes. */
  createdSecret: string | null = null;
  showSecretDialog = false;
  copied = false;
  private copiedTimer?: ReturnType<typeof setTimeout>;

  constructor(
    private tokenService: PersonalAccessTokenService,
    private confirmationService: ConfirmationService,
    private toast: UiToastService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.tokenService.listScopes().subscribe({
      next: (scopes) => (this.scopes = scopes),
      error: () => (this.scopes = []),
    });
  }

  ngOnDestroy(): void {
    this.clearSecret();
  }

  load(): void {
    this.loading = true;
    this.error = undefined;
    this.tokenService
      .list()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (tokens) => (this.tokens = tokens),
        error: () => (this.error = 'Error loading access tokens'),
      });
  }

  get activeCount(): number {
    return this.tokens.filter((t) => t.status === 'ACTIVE').length;
  }

  isProcessing(publicId: string): boolean {
    return this.processingIds.has(publicId);
  }

  scopeDescription(value: string): string {
    return this.scopes.find((s) => s.value === value)?.description ?? value;
  }

  // --- create ------------------------------------------------------------------

  openCreate(): void {
    this.name = '';
    this.selectedScopes = new Set<string>();
    this.expiresInDays = 30;
    this.currentPassword = '';
    this.showPassword = false;
    this.createError = undefined;
    this.showCreateDialog = true;
  }

  onCreateDialogHide(): void {
    // Never keep the password around after the dialog closes.
    this.currentPassword = '';
    this.showPassword = false;
  }

  toggleScope(value: string, checked: boolean): void {
    if (checked) {
      this.selectedScopes.add(value);
    } else {
      this.selectedScopes.delete(value);
    }
  }

  get canSubmit(): boolean {
    const trimmed = this.name.trim();
    return (
      !this.creating &&
      trimmed.length > 0 &&
      trimmed.length <= this.maxNameLength &&
      this.selectedScopes.size > 0 &&
      this.expiryOptions.includes(Number(this.expiresInDays)) &&
      this.currentPassword.length > 0
    );
  }

  submitCreate(): void {
    if (!this.canSubmit) return;
    this.creating = true;
    this.createError = undefined;
    this.tokenService
      .create({
        name: this.name.trim(),
        scopes: [...this.selectedScopes],
        expiresInDays: Number(this.expiresInDays),
        currentPassword: this.currentPassword,
      })
      .pipe(finalize(() => (this.creating = false)))
      .subscribe({
        next: (created) => {
          this.showCreateDialog = false;
          this.currentPassword = '';
          this.tokens = [created.metadata, ...this.tokens];
          this.createdSecret = created.token;
          this.copied = false;
          this.showSecretDialog = true;
        },
        error: (err: HttpErrorResponse) => {
          this.currentPassword = '';
          this.createError = this.describeCreateError(err);
        },
      });
  }

  private describeCreateError(err: HttpErrorResponse): string {
    switch (err.status) {
      case 401:
        return 'Your current password is incorrect.';
      case 400:
        return 'The token could not be created. Check the name, scopes and expiry, or revoke unused tokens (limit: 20 active).';
      case 429:
        return 'Too many attempts. Please wait a minute and try again.';
      default:
        return 'The token could not be created. Please try again.';
    }
  }

  // --- reveal once -------------------------------------------------------------

  copySecret(): void {
    const secret = this.createdSecret;
    if (!secret) return;
    if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) {
      this.toast.error('Copy failed', 'Please select and copy the token.');
      return;
    }
    navigator.clipboard
      .writeText(secret)
      .then(() => {
        this.copied = true;
        this.toast.success('Copied', 'Access token copied to clipboard.');
        clearTimeout(this.copiedTimer);
        this.copiedTimer = setTimeout(() => (this.copied = false), 2000);
      })
      .catch(() =>
        this.toast.error('Copy failed', 'Please select and copy the token.'),
      );
  }

  closeSecretDialog(): void {
    this.showSecretDialog = false;
    this.clearSecret();
  }

  clearSecret(): void {
    this.createdSecret = null;
    this.copied = false;
    clearTimeout(this.copiedTimer);
  }

  // --- revoke ------------------------------------------------------------------

  confirmRevoke(token: PersonalAccessTokenDto): void {
    if (this.isProcessing(token.publicId) || token.status !== 'ACTIVE') return;
    this.confirmationService.confirm({
      header: 'Revoke access token',
      message: `Clients using "${token.name}" will be rejected on their next request. This cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Revoke',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.revoke(token),
    });
  }

  private revoke(token: PersonalAccessTokenDto): void {
    this.processingIds.add(token.publicId);
    this.tokenService
      .revoke(token.publicId)
      .pipe(finalize(() => this.processingIds.delete(token.publicId)))
      .subscribe({
        next: (updated) => {
          this.tokens = this.tokens.map((t) =>
            t.publicId === updated.publicId ? updated : t,
          );
          this.toast.success(
            'Token revoked',
            `"${token.name}" can no longer be used.`,
          );
        },
        error: () =>
          this.toast.error('Revocation failed', 'The token was not changed.'),
      });
  }
}
