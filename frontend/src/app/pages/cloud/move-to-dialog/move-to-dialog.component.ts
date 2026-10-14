import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { FolderDto } from '../../../models/dtos/FolderDto';
import { FolderListItemDto } from '../../../models/dtos/FolderListItemDto';
import { CloudService } from '../../../services/cloud.service';

export interface MoveItem {
  path: string;
  name: string;
  kind: 'file' | 'folder';
}

export interface MoveConfirmedEvent {
  targetPath: string;
  existingNames: string[];
}

interface PickerCrumb {
  name: string;
  /** Full folder path; null for the root. */
  path: string | null;
}

/**
 * Folder picker for moving files and folders without drag and drop. It only
 * reads folders; the parent performs the move when `moveConfirmed` fires.
 */
@Component({
  selector: 'app-move-to-dialog',
  standalone: true,
  imports: [CommonModule, DialogModule, ButtonModule],
  templateUrl: './move-to-dialog.component.html',
})
export class MoveToDialogComponent implements OnChanges {
  @Input() visible = false;
  @Output() visibleChange = new EventEmitter<boolean>();
  @Input() items: MoveItem[] = [];
  @Input() canMoveTo: (targetPath: string) => boolean = () => true;
  @Input() toRelative: (path: string) => string = (path) => path;
  /** True while the parent is moving, so "Move here" can't be pressed twice. */
  @Input() busy = false;
  /** Receives focus on close when the element that opened the dialog is gone. */
  @Input() focusFallback: HTMLElement | null = null;
  @Output() moveConfirmed = new EventEmitter<MoveConfirmedEvent>();

  folder: FolderDto | null = null;
  breadcrumbs: PickerCrumb[] = [];
  loading = false;
  loadError: string | null = null;

  private rootPath: string | null = null;
  private browsedPath: string | null = null;
  private requestId = 0;
  private opener: HTMLElement | null = null;

  constructor(private cloudService: CloudService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['visible'] && this.visible) {
      this.opener =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
      this.folder = null;
      this.breadcrumbs = [];
      this.rootPath = null;
      this.openFolder(null);
    }
  }

  get header(): string {
    return this.items.length === 1
      ? `Move "${this.items[0].name}"`
      : `Move ${this.items.length} items`;
  }

  get canGoUp(): boolean {
    return this.breadcrumbs.length > 1 && !this.loading;
  }

  get canMoveHere(): boolean {
    return (
      !!this.folder &&
      !this.loading &&
      !this.loadError &&
      !this.busy &&
      this.canMoveTo(this.folder.path)
    );
  }

  /** Why "Move here" is disabled for the browsed folder, if it is. */
  get moveHereReason(): string {
    if (!this.folder || this.loading || this.loadError) return '';
    if (this.canMoveTo(this.folder.path)) return '';

    const target = this.toRelative(this.folder.path);
    const alreadyHere = this.items.find(
      (item) => this.parentOf(this.toRelative(item.path)) === target,
    );
    if (alreadyHere) {
      return `"${alreadyHere.name}" is already in this folder.`;
    }
    return "The selected items can't be moved here.";
  }

  /** A folder being moved, or one inside it, can't be opened as a target. */
  isBlocked(folder: FolderListItemDto): boolean {
    const candidate = this.toRelative(folder.path);
    return this.items.some((item) => {
      if (item.kind !== 'folder') return false;
      const moved = this.toRelative(item.path);
      return candidate === moved || candidate.startsWith(moved + '/');
    });
  }

  openFolder(path: string | null): void {
    const requestId = ++this.requestId;
    this.browsedPath = path;
    this.loading = true;
    this.loadError = null;

    const folder$ =
      path === null
        ? this.cloudService.getRootFolder(true)
        : this.cloudService.getFolderByPath(this.toRelative(path), true);

    folder$.subscribe({
      next: (folder) => {
        if (requestId !== this.requestId) return;
        if (this.rootPath === null) this.rootPath = folder.path;
        this.folder = folder;
        this.breadcrumbs = this.buildBreadcrumbs(folder.path);
        this.loading = false;
      },
      error: () => {
        if (requestId !== this.requestId) return;
        this.loadError = 'Could not load this folder.';
        this.loading = false;
      },
    });
  }

  retry(): void {
    this.openFolder(this.browsedPath);
  }

  goUp(): void {
    if (!this.canGoUp) return;
    this.openFolder(this.breadcrumbs[this.breadcrumbs.length - 2].path);
  }

  moveHere(): void {
    if (!this.folder || !this.canMoveHere) return;
    this.moveConfirmed.emit({
      targetPath: this.folder.path,
      existingNames: [
        ...(this.folder.folders ?? []).map((f) => f.name),
        ...(this.folder.files ?? []).map((f) => f.name),
      ],
    });
  }

  cancel(): void {
    this.visibleChange.emit(false);
  }

  onVisibleChange(visible: boolean): void {
    if (!visible) this.visibleChange.emit(false);
  }

  /** PrimeNG's dialog doesn't return focus on close, so do it here. */
  onHide(): void {
    const target =
      this.opener && this.opener.isConnected ? this.opener : this.focusFallback;
    this.opener = null;
    target?.focus();
  }

  private buildBreadcrumbs(folderPath: string): PickerCrumb[] {
    const crumbs: PickerCrumb[] = [{ name: 'My files', path: null }];
    const relative = this.toRelative(folderPath);
    if (relative === '/') return crumbs;

    let accumulated = this.rootPath ?? '';
    for (const part of relative.split('/')) {
      accumulated = `${accumulated}/${part}`;
      crumbs.push({ name: part, path: accumulated });
    }
    return crumbs;
  }

  private parentOf(relativePath: string): string {
    const lastSlash = relativePath.lastIndexOf('/');
    return lastSlash <= 0 ? '/' : relativePath.substring(0, lastSlash);
  }
}
