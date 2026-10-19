import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { ConfirmationService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';
import { TrashEntryDto } from '../../../models/dtos/TrashEntryDto';
import { CloudService } from '../../../services/cloud.service';
import { UiToastService } from '../../../core/services/ui-toast.service';
import { formatFileSize } from '../../../core/utils/file-size.util';

@Component({
  selector: 'app-trash',
  standalone: true,
  imports: [
    CommonModule,
    TableModule,
    ButtonModule,
    ConfirmDialogModule,
    TooltipModule,
  ],
  providers: [ConfirmationService],
  templateUrl: './trash.component.html',
  styleUrls: ['./trash.component.scss'],
})
export class TrashComponent implements OnInit {
  entries: TrashEntryDto[] = [];
  loading = true;
  error?: string;
  processingIds = new Set<string>();

  constructor(
    private cloudService: CloudService,
    private confirmationService: ConfirmationService,
    private toast: UiToastService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.loadTrash();
  }

  loadTrash(): void {
    this.loading = true;
    this.error = undefined;
    this.cloudService.listTrash().subscribe({
      next: (entries) => {
        this.entries = entries;
        this.loading = false;
      },
      error: () => {
        this.error = 'Error loading trash';
        this.toast.error(
          'Could not load trash',
          'Trash contents are unavailable.',
        );
        this.loading = false;
      },
    });
  }

  goToCloud(): void {
    this.router.navigate(['/cloud']);
  }

  isProcessing(id: string): boolean {
    return this.processingIds.has(id);
  }

  restore(entry: TrashEntryDto): void {
    this.processingIds.add(entry.id);
    this.cloudService
      .restoreTrashEntry(entry.id)
      .pipe(finalize(() => this.processingIds.delete(entry.id)))
      .subscribe({
        next: () => {
          this.entries = this.entries.filter((e) => e.id !== entry.id);
          this.toast.success(
            'File restored',
            `"${entry.name}" was restored to ${entry.originalPath}.`,
          );
        },
        error: (err) => {
          if (this.isConflict(err)) {
            this.toast.error(
              'Restore conflict',
              `A file already exists at ${entry.originalPath}. Rename or move it, then try again.`,
            );
          } else {
            this.toast.error('Restore failed', this.getErrorMessage(err));
          }
        },
      });
  }

  confirmPurge(entry: TrashEntryDto): void {
    this.confirmationService.confirm({
      header: 'Delete permanently',
      message: `Permanently delete "${entry.name}"? This cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.purge(entry),
    });
  }

  purge(entry: TrashEntryDto): void {
    this.processingIds.add(entry.id);
    this.cloudService
      .purgeTrashEntry(entry.id)
      .pipe(finalize(() => this.processingIds.delete(entry.id)))
      .subscribe({
        next: () => {
          this.entries = this.entries.filter((e) => e.id !== entry.id);
          this.toast.success(
            'File deleted',
            `"${entry.name}" was permanently deleted.`,
          );
        },
        error: (err) =>
          this.toast.error('Delete failed', this.getErrorMessage(err)),
      });
  }

  formatFileSize(bytes: number): string {
    return formatFileSize(bytes);
  }

  // The backend signals a restore collision with FileAlreadyExistsException;
  // accept either a 409 or the "already exists" message so the UX stays graceful
  // regardless of how that surfaces.
  private isConflict(err: unknown): boolean {
    const candidate = err as {
      status?: number;
      error?: unknown;
      message?: string;
    };
    if (candidate?.status === 409) return true;
    const body =
      typeof candidate?.error === 'string'
        ? candidate.error
        : (candidate?.error as { message?: string })?.message ||
          candidate?.message ||
          '';
    return /already exists/i.test(body);
  }

  private getErrorMessage(err: unknown): string {
    const candidate = err as {
      message?: string;
      error?: { message?: string } | string;
    };
    if (typeof candidate?.error === 'string' && candidate.error.trim()) {
      return candidate.error;
    }
    return (
      (candidate?.error as { message?: string })?.message ||
      candidate?.message ||
      'Request failed. Please try again.'
    );
  }
}
