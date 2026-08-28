import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { ConfirmationService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';
import { ResourceShareDto } from '../../../models/dtos/ResourceShareDto';
import { SecureSendLinkDto } from '../../../models/dtos/SecureSendLinkDto';
import { CloudService } from '../../../services/cloud.service';
import { ResourceShareService } from '../../../services/resource-share.service';
import { UiToastService } from '../../../core/services/ui-toast.service';

type ShareView = 'sent' | 'links';

/**
 * Manages sharing the user gives out: files/folders shared with other members
 * (revoke access) and expiring public links (revoke). Receiving and browsing
 * shared content lives in the main cloud view under the "Shared with me" folder,
 * so this page deliberately has no browser of its own.
 */
@Component({
  selector: 'app-shared-links',
  standalone: true,
  imports: [
    CommonModule,
    TableModule,
    ButtonModule,
    ConfirmDialogModule,
    TooltipModule,
  ],
  providers: [ConfirmationService],
  templateUrl: './shared-links.component.html',
  styleUrls: ['./shared-links.component.scss'],
})
export class SharedLinksComponent implements OnInit {
  view: ShareView = 'sent';

  sent: ResourceShareDto[] = [];
  links: SecureSendLinkDto[] = [];

  loading = true;
  error?: string;
  processingIds = new Set<string>();

  constructor(
    private cloudService: CloudService,
    private shareService: ResourceShareService,
    private confirmationService: ConfirmationService,
    private toast: UiToastService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading = true;
    this.error = undefined;
    this.shareService.listOwned().subscribe({
      next: (shares) => (this.sent = shares),
      error: () => (this.error = 'Error loading shares'),
    });
    this.cloudService
      .listSecureSendLinks()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (links) => (this.links = links),
        error: () => (this.error = 'Error loading shares'),
      });
  }

  switchView(view: ShareView): void {
    this.view = view;
  }

  goToCloud(): void {
    this.router.navigate(['/cloud']);
  }

  isProcessing(id: string): boolean {
    return this.processingIds.has(id);
  }

  permissionLabel(share: ResourceShareDto): string {
    const order = ['VIEW', 'DOWNLOAD', 'EDIT'] as const;
    return order
      .filter((p) => share.permissions.includes(p))
      .map((p) => p.charAt(0) + p.slice(1).toLowerCase())
      .join(', ');
  }

  // --- shares I gave out -----------------------------------------------------

  confirmRevokeShare(share: ResourceShareDto): void {
    this.confirmationService.confirm({
      header: 'Revoke access',
      message: `${share.recipientUsername} will lose access to "${share.displayName}" immediately.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Revoke',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.revokeShare(share),
    });
  }

  private revokeShare(share: ResourceShareDto): void {
    this.processingIds.add(share.id);
    this.shareService
      .revoke(share.id)
      .pipe(finalize(() => this.processingIds.delete(share.id)))
      .subscribe({
        next: () => {
          this.sent = this.sent.filter((s) => s.id !== share.id);
          this.toast.success(
            'Access revoked',
            `${share.recipientUsername} can no longer reach "${share.displayName}".`,
          );
        },
        error: () =>
          this.toast.error('Revocation failed', 'Access was not changed.'),
      });
  }

  // --- public links ----------------------------------------------------------

  isExpired(link: SecureSendLinkDto): boolean {
    return (
      link.expiresAt != null && new Date(link.expiresAt).getTime() <= Date.now()
    );
  }

  linkStatus(
    link: SecureSendLinkDto,
  ): 'revoked' | 'expired' | 'active' | 'never-expires' {
    if (link.isRevoked) {
      return 'revoked';
    }
    if (link.expiresAt == null) {
      return 'never-expires';
    }
    return this.isExpired(link) ? 'expired' : 'active';
  }

  confirmRevokeLink(link: SecureSendLinkDto): void {
    this.confirmationService.confirm({
      header: 'Revoke share link',
      message: `Anyone holding the link to "${link.fileName}" will lose access immediately. This cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Revoke',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.revokeLink(link),
    });
  }

  private revokeLink(link: SecureSendLinkDto): void {
    this.processingIds.add(link.id);
    this.cloudService
      .revokeSecureSendLink(link.id)
      .pipe(finalize(() => this.processingIds.delete(link.id)))
      .subscribe({
        next: () => {
          link.isRevoked = true;
          link.revokedAt = new Date().toISOString();
          this.toast.success(
            'Link revoked',
            `The share link for "${link.fileName}" no longer works.`,
          );
        },
        error: () =>
          this.toast.error(
            'Revocation failed',
            'The share link could not be revoked.',
          ),
      });
  }

  confirmDeleteLink(link: SecureSendLinkDto): void {
    this.confirmationService.confirm({
      header: 'Delete share link',
      message: `This permanently removes the link to "${link.fileName}" from your list. This cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.deleteLink(link),
    });
  }

  private deleteLink(link: SecureSendLinkDto): void {
    this.processingIds.add(link.id);
    this.cloudService
      .deleteSecureSendLink(link.id)
      .pipe(finalize(() => this.processingIds.delete(link.id)))
      .subscribe({
        next: () => {
          this.links = this.links.filter((l) => l.id !== link.id);
          this.toast.success(
            'Link deleted',
            `The share link for "${link.fileName}" has been removed.`,
          );
        },
        error: () =>
          this.toast.error(
            'Delete failed',
            'The share link could not be deleted.',
          ),
      });
  }
}
