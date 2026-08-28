import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { MenuItem, ConfirmationService } from 'primeng/api';
import { BreadcrumbModule } from 'primeng/breadcrumb';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { Menu } from 'primeng/menu';
import { MenuModule } from 'primeng/menu';
import { TooltipModule } from 'primeng/tooltip';
import { TableLazyLoadEvent, TableModule } from 'primeng/table';
import { ToolbarModule } from 'primeng/toolbar';
import { FileDto } from '../../models/dtos/FileDto';
import { FolderDto } from '../../models/dtos/FolderDto';
import { FolderContentItemDto } from '../../models/dtos/FolderContentItemDto';
import { SearchResultDto } from '../../models/dtos/SearchResultDto';
import { ScanJobDto } from '../../models/dtos/ScanJobDto';
import { FileScanResultDto } from '../../models/dtos/FileScanResultDto';
import { FileChecksumDto } from '../../models/dtos/FileChecksumDto';
import { CloudService } from '../../services/cloud.service';
import {
  Observable,
  catchError,
  finalize,
  firstValueFrom,
  from,
  map,
  mergeMap,
  of,
  tap,
} from 'rxjs';
import { UiToastService } from '../../core/services/ui-toast.service';
import {
  ResourceShareDto,
  SharePermission,
} from '../../models/dtos/ResourceShareDto';
import { UserDto } from '../../models/dtos/UserDto';
import { ResourceShareService } from '../../services/resource-share.service';
import { UserService } from '../../services/user.service';

interface Breadcrumb {
  name: string;
  path: string;
}

const SIZE_REQUEST_CONCURRENCY = 3;

interface CloudEntry {
  kind: 'folder' | 'file';
  name: string;
  path: string;
  sizeLabel: string;
  typeLabel: string;
  lastModifiedAt: number;
}

type CloudSort =
  | 'name,asc'
  | 'name,desc'
  | 'lastModifiedAt,asc'
  | 'lastModifiedAt,desc'
  | 'size,asc'
  | 'size,desc';

@Component({
  selector: 'app-cloud',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TableModule,
    ButtonModule,
    ToolbarModule,
    MenuModule,
    BreadcrumbModule,
    DialogModule,
    InputTextModule,
    ConfirmDialogModule,
    TooltipModule,
  ],
  providers: [ConfirmationService],
  templateUrl: './cloud.component.html',
  styleUrls: ['./cloud.component.scss'],
})
export class CloudComponent implements OnInit, OnDestroy {
  @ViewChild('fileUploadInput') fileUploadInput?: ElementRef<HTMLInputElement>;

  currentFolder?: FolderDto;
  loading = true;
  error?: string;
  breadcrumbs: Breadcrumb[] = [];
  rootPath = '';

  showFileEditor = false;
  editingFile: FileDto | null = null;
  newFileName = '';
  fileContent = '';
  // The editor state as last loaded or saved.
  originalFileName = '';
  originalFileContent = '';
  outline: { text: string; level: number }[] = [];
  saveStatus: 'saved' | 'saving' | 'unsaved' = 'saved';
  editorMode: 'edit' | 'preview' | 'split' = 'edit';
  previewHtml: SafeHtml = '';

  showCreateFolderDialog = false;
  showRenameFolderDialog = false;
  showRenameFileDialog = false;
  showCreateShareDialog = false;
  showGeneratedLinkDialog = false;

  selectedFileForShare: { path: string; name: string } | null = null;
  /** A folder link is browsable, which the dialog says out loud before creating it. */
  selectedShareIsFolder = false;
  shareExpiryMinutes = 1440;
  sharePassword = '';
  createdShareUrl = '';
  creatingShareLink = false;
  showShareWithMemberDialog = false;
  selectedEntryForMemberShare: { path: string; name: string } | null = null;
  shareCandidates: UserDto[] = [];
  shareRecipientUsername = '';
  shareAllowDownload = true;
  shareAllowEdit = false;
  creatingMemberShare = false;
  isAtRoot = true;

  // "Shared with me" browsing, rendered inside the normal cloud view so the same
  // table, toolbar and editor are reused. `inSharedRoot` shows the list of
  // received shares; `shareCtx` means we are inside one share at sub-path `sub`.
  // Both null/false => ordinary own-storage behaviour, left untouched.
  readonly SHARED_ROOT_TOKEN = '__shared-with-me__';
  private readonly SHARE_ENTRY_PREFIX = '__share:';
  inSharedRoot = false;
  shareCtx: { share: ResourceShareDto; sub: string } | null = null;
  private receivedShares: ResourceShareDto[] = [];

  // Who currently has access, for the avatar stack on own shared folders.
  // sharesByPath: own active shares grouped by their folder path (relative to
  // the user's root). avatarByUsername: username -> profile-picture URL (or null
  // -> render an initial).
  private sharesByPath = new Map<string, ResourceShareDto[]>();
  private avatarByUsername = new Map<string, string | null>();

  expiryOptions = [
    { label: '1 Hour', value: 60 },
    { label: '1 Day', value: 1440 },
    { label: '7 Days', value: 10080 },
    { label: '30 Days', value: 43200 },
    { label: 'Never', value: 0 },
  ];

  newFolderName = '';
  renameFolderName = '';
  renameFileName = '';
  selectedFolderPathForRename: string | null = null;
  selectedFolderNameForRename: string | null = null;
  selectedFileForRename: FileDto | null = null;

  createMenuItems: MenuItem[] = [];
  sortMenuItems: MenuItem[] = [];
  sort: CloudSort = 'name,asc';
  entries: CloudEntry[] = [];
  downloadingPaths = new Set<string>();

  searchQuery = '';
  searchActive = false;
  searching = false;

  showScanDialog = false;
  scanning = false;
  scanJob?: ScanJobDto;
  scanError?: string;
  scanFolderLabel = '';
  private scanJobId?: string;
  private scanPollHandle?: ReturnType<typeof setTimeout>;
  private readonly scanPollIntervalMs = 1200;
  // A 429 while polling means we asked too often, not that the scan died — it
  // keeps running on the server. Back off and keep waiting instead of tearing
  // the dialog down, giving up only if the backend stays rate limited.
  private scanRateLimitedPolls = 0;
  private readonly maxRateLimitedPolls = 5;
  private readonly scanRateLimitBackoffMs = 5000;
  // Bumped whenever a scan starts or the dialog closes; in-flight start/poll
  // callbacks compare against it and bail if they've been superseded (mirrors
  // the contentRequestId guard used for folder loads/searches).
  private scanRunId = 0;

  private checksumRequestId = 0;
  showChecksumDialog = false;
  checksumLoading = false;
  checksumError?: string;
  checksumResult?: FileChecksumDto;
  selectedFileForChecksum?: { name: string; path: string } | null;
  expectedHash = '';
  copiedHashState = false;

  pageSize = 50;
  totalElements = 0;
  contentFirst = 0;
  private contentPage = 0;
  private contentRequestId = 0;

  private draggedPath: string | null = null;
  private draggedIsFolder = false;
  draggedOverPath: string | null = null;
  isExternalDrag = false;

  constructor(
    private cloudService: CloudService,
    private confirmationService: ConfirmationService,
    private toast: UiToastService,
    private router: Router,
    private sanitizer: DomSanitizer,
    private resourceShareService: ResourceShareService,
    private userService: UserService,
  ) {}

  private getErrorMessage(err: unknown): string {
    const candidate = err as {
      message?: string;
      error?: { message?: string };
    };
    return (
      candidate?.error?.message ||
      candidate?.message ||
      'Request failed. Please try again.'
    );
  }

  ngOnInit(): void {
    this.loadSharingMeta();
    this.createMenuItems = [
      {
        label: 'New Folder',
        icon: 'pi pi-folder',
        command: () => this.openCreateFolderDialog(),
      },
      {
        label: 'New File',
        icon: 'pi pi-file',
        command: () => this.createNewFile(),
      },
      {
        label: 'Upload',
        icon: 'pi pi-upload',
        command: () => this.openFileUploadDialog(),
      },
    ];
    this.sortMenuItems = [
      {
        label: 'Name (A–Z)',
        icon: 'pi pi-sort-alpha-down',
        command: () => this.setSort('name,asc'),
      },
      {
        label: 'Name (Z–A)',
        icon: 'pi pi-sort-alpha-up-alt',
        command: () => this.setSort('name,desc'),
      },
      {
        label: 'Newest first',
        icon: 'pi pi-sort-amount-down',
        command: () => this.setSort('lastModifiedAt,desc'),
      },
      {
        label: 'Oldest first',
        icon: 'pi pi-sort-amount-up',
        command: () => this.setSort('lastModifiedAt,asc'),
      },
      {
        label: 'Largest first',
        icon: 'pi pi-sort-amount-down',
        command: () => this.setSort('size,desc'),
      },
      {
        label: 'Smallest first',
        icon: 'pi pi-sort-amount-up',
        command: () => this.setSort('size,asc'),
      },
    ];
    this.loadRootFolder();
  }

  get breadcrumbItems(): MenuItem[] {
    return this.breadcrumbs.map((crumb) => ({
      label: crumb.name,
      id: crumb.path,
      command: () => this.navigateToFolder(crumb.path),
    }));
  }

  get homeBreadcrumb(): MenuItem {
    return {
      icon: 'pi pi-home',
      id: this.rootPath,
      command: () => this.navigateToRoot(),
    };
  }

  get totalItemsInView(): number {
    return this.totalElements;
  }

  private buildEntries(items: FolderContentItemDto[]): CloudEntry[] {
    return items.map((item) => ({
      kind: item.directory ? 'folder' : 'file',
      name: item.name,
      path: item.path,
      sizeLabel:
        item.directory && item.size < 0 ? '…' : this.formatFileSize(item.size),
      typeLabel: item.directory ? 'Folder' : item.mimeType || 'Unknown',
      lastModifiedAt: item.lastModifiedAt,
    }));
  }

  private loadDirectorySizes(entries: CloudEntry[], requestId: number) {
    const pending = entries.filter(
      (entry) => entry.kind === 'folder' && entry.sizeLabel === '…',
    );
    if (!pending.length) return;

    from(pending)
      .pipe(
        mergeMap(
          (entry) =>
            this.cloudService.getFolderSize(entry.path).pipe(
              map((size) => ({ entry, size })),
              catchError(() => of({ entry, size: -1 })),
            ),
          SIZE_REQUEST_CONCURRENCY,
        ),
      )
      .subscribe(({ entry, size }) => {
        if (requestId !== this.contentRequestId) return;
        entry.sizeLabel = size < 0 ? '—' : this.formatFileSize(size);
      });
  }

  private buildSearchEntries(results: SearchResultDto[]): CloudEntry[] {
    return results.map((result) => ({
      kind: result.type === 'folder' ? 'folder' : 'file',
      name: result.name,
      path: result.path,
      sizeLabel: this.formatFileSize(result.size ?? 0),
      typeLabel:
        result.type === 'folder' ? 'Folder' : result.mimeType || 'Unknown',
      lastModifiedAt: result.lastModifiedAt,
    }));
  }

  private loadFolderContent(relativePath: string, page: number) {
    // The "Shared with me" entry point only belongs at the top of the user's
    // own root, not inside sub-folders or search results.
    this.isAtRoot = relativePath === '/' || relativePath === '';
    // Guard against out-of-order responses: when the user navigates or pages
    // quickly, an earlier (slower) request must not overwrite newer state.
    const requestId = ++this.contentRequestId;
    this.cloudService
      .getFolderContent(relativePath, page, this.pageSize, this.sort, false)
      .subscribe({
        next: (contentPage) => {
          if (requestId !== this.contentRequestId) return;
          this.entries = this.buildEntries(contentPage.content);
          // Surface "Shared with me" as the first entry of the own root, so it
          // sits among the user's normal folders and opens in this same view.
          if (
            this.isAtRoot &&
            contentPage.pageNumber === 0 &&
            !this.searchActive
          ) {
            this.entries = [this.sharedRootEntry(), ...this.entries];
          }
          this.totalElements = contentPage.totalElements;
          this.contentPage = contentPage.pageNumber;
          this.loading = false;
          this.loadDirectorySizes(this.entries, requestId);
        },
        error: () => {
          if (requestId !== this.contentRequestId) return;
          this.error = 'Error loading folder contents';
          this.toast.error(
            'Could not load folder',
            'Folder contents are unavailable.',
          );
          this.loading = false;
        },
      });
  }

  onPageChange(event: TableLazyLoadEvent) {
    const rows = event.rows ?? this.pageSize;
    const first = Array.isArray(event.first)
      ? (event.first[0] ?? 0)
      : (event.first ?? 0);
    const page = Math.floor(first / rows);
    if (page === this.contentPage && rows === this.pageSize) return;
    this.contentFirst = first;
    this.pageSize = rows;
    this.loading = true;
    const relativePath = this.getRelativePath(
      this.currentFolder?.path || this.rootPath,
    );
    this.loadFolderContent(relativePath, page);
  }

  setSort(sort: CloudSort) {
    if (this.sort === sort) return;
    this.sort = sort;
    this.searchActive = false;
    this.searchQuery = '';
    this.searching = false;
    this.contentFirst = 0;
    this.loading = true;
    const relativePath = this.getRelativePath(
      this.currentFolder?.path || this.rootPath,
    );
    this.loadFolderContent(relativePath, 0);
  }

  onSearch() {
    const query = this.searchQuery.trim();
    if (!query) {
      this.clearSearch();
      return;
    }
    const relativePath = this.getRelativePath(
      this.currentFolder?.path || this.rootPath,
    );
    const wasSearchActive = this.searchActive;
    this.searching = true;
    this.searchActive = true;
    const requestId = ++this.contentRequestId;
    this.cloudService.searchInFolder(relativePath, query, 100).subscribe({
      next: (results) => {
        if (requestId !== this.contentRequestId) return;
        this.entries = this.buildSearchEntries(results);
        this.totalElements = this.entries.length;
        this.searching = false;
      },
      error: (err) => {
        if (requestId !== this.contentRequestId) return;
        this.searching = false;
        // Restore the prior mode so a failed search doesn't strand the UI in
        // "search mode" (no pagination, wrong subtitle) over folder contents.
        this.searchActive = wasSearchActive;
        this.toast.error('Search failed', this.getErrorMessage(err));
      },
    });
  }

  clearSearch() {
    if (!this.searchActive && this.searchQuery === '') return;
    this.searchQuery = '';
    this.searchActive = false;
    this.searching = false;
    this.contentFirst = 0;
    this.loading = true;
    const relativePath = this.getRelativePath(
      this.currentFolder?.path || this.rootPath,
    );
    this.loadFolderContent(relativePath, 0);
  }

  loadRootFolder() {
    this.searchActive = false;
    this.searchQuery = '';
    this.searching = false;
    this.loading = true;
    this.error = undefined;
    this.contentFirst = 0;
    // Invalidate any in-flight search/content request so a stale, fast
    // response can't overwrite the root reload that's about to start.
    this.contentRequestId++;
    this.cloudService.getRootFolder(false).subscribe({
      next: (folder) => {
        this.currentFolder = folder;
        this.rootPath = folder.path;
        this.updateBreadcrumbs(folder.path);
        this.loadFolderContent('/', 0);
      },
      error: () => {
        this.error = 'Error loading root folder';
        this.toast.error(
          'Could not load folder',
          'Root folder is unavailable.',
        );
        this.loading = false;
      },
    });
  }

  reloadRootFolder() {
    this.loadRootFolder();
  }

  goToTrash() {
    this.router.navigate(['/cloud/trash']);
  }

  goToSharedLinks() {
    this.router.navigate(['/cloud/shared']);
  }

  // --- "Shared with me" inside the normal cloud view ------------------------

  /** True while browsing the shared area (list of shares or inside one). */
  get sharedMode(): boolean {
    return this.inSharedRoot || !!this.shareCtx;
  }

  /**
   * Whether write actions (new file/folder, upload, delete, save) apply here.
   * Always true in own storage; inside a share only with the EDIT permission.
   */
  get canWriteHere(): boolean {
    if (!this.sharedMode) {
      return true;
    }
    return !!this.shareCtx?.share.permissions.includes('EDIT');
  }

  /**
   * Whether files here may be taken out of the vault. Always true in own storage;
   * inside a share only with DOWNLOAD, so a VIEW-only share offers no download
   * button instead of failing against the backend.
   */
  get canDownloadHere(): boolean {
    if (!this.sharedMode) {
      return true;
    }
    return !!this.shareCtx?.share.permissions.includes('DOWNLOAD');
  }

  /** Synthetic root entry that opens the shared area, shown among own folders. */
  private sharedRootEntry(): CloudEntry {
    return {
      kind: 'folder',
      name: 'Shared with me',
      path: this.SHARED_ROOT_TOKEN,
      sizeLabel: '',
      typeLabel: 'Shared',
      lastModifiedAt: 0,
    };
  }

  /** Single dispatch for a row click, routing to own storage or a share. */
  onEntryOpen(entry: CloudEntry): void {
    if (entry.path === this.SHARED_ROOT_TOKEN) {
      this.enterSharedRoot();
      return;
    }
    if (this.inSharedRoot) {
      this.openShareEntry(entry);
      return;
    }
    if (this.shareCtx) {
      if (entry.kind === 'folder') {
        this.loadShareContent(entry.path);
      } else {
        this.openSharedFile(entry);
      }
      return;
    }
    if (entry.kind === 'folder') {
      this.navigateToFolder(entry.path);
    } else {
      this.previewFileByPath(entry.path, entry.name);
    }
  }

  enterSharedRoot(): void {
    this.inSharedRoot = true;
    this.shareCtx = null;
    this.isAtRoot = false;
    this.searchActive = false;
    this.searchQuery = '';
    this.loading = true;
    this.error = undefined;
    this.contentRequestId++;
    this.resourceShareService.listReceived().subscribe({
      next: (shares) => {
        this.receivedShares = shares.filter((s) => !s.revoked);
        this.entries = this.receivedShares.map((s) => ({
          kind: s.resourceType === 'FOLDER' ? 'folder' : 'file',
          name: `${s.displayName} — from ${s.ownerUsername}`,
          path: this.SHARE_ENTRY_PREFIX + s.id,
          sizeLabel: '',
          typeLabel:
            s.resourceType === 'FOLDER' ? 'Shared folder' : 'Shared file',
          lastModifiedAt: new Date(s.createdAt).getTime(),
        }));
        this.totalElements = this.entries.length;
        this.loading = false;
      },
      error: () => {
        this.error = 'Error loading shared items';
        this.loading = false;
      },
    });
  }

  private openShareEntry(entry: CloudEntry): void {
    const id = entry.path.slice(this.SHARE_ENTRY_PREFIX.length);
    const share = this.receivedShares.find((s) => s.id === id);
    if (!share) {
      return;
    }
    this.shareCtx = { share, sub: '' };
    if (share.resourceType === 'FOLDER') {
      this.loadShareContent('');
    } else {
      // A shared single file: open it directly like any file.
      this.openSharedFile({
        kind: 'file',
        name: share.displayName,
        path: '',
        sizeLabel: '',
        typeLabel: 'file',
        lastModifiedAt: 0,
      });
    }
  }

  loadShareContent(sub: string): void {
    if (!this.shareCtx) {
      return;
    }
    this.shareCtx = { share: this.shareCtx.share, sub };
    this.loading = true;
    this.error = undefined;
    const requestId = ++this.contentRequestId;
    this.resourceShareService
      .listContent(this.shareCtx.share.id, sub)
      .subscribe({
        next: (items) => {
          if (requestId !== this.contentRequestId) return;
          this.entries = this.buildEntries(items);
          this.totalElements = items.length;
          this.loading = false;
        },
        error: (err) => {
          if (requestId !== this.contentRequestId) return;
          this.loading = false;
          // 404 = the owner removed the folder (or revoked). Don't leave the
          // previous rows on screen with an active share context; go back to
          // the (now refreshed) shared list so the stale item disappears.
          if ((err as { status?: number })?.status === 404) {
            this.toast.info(
              'No longer available',
              'The owner removed or stopped sharing this item.',
            );
            this.enterSharedRoot();
            return;
          }
          this.error = 'Error loading shared folder';
        },
      });
  }

  private openSharedFile(entry: CloudEntry): void {
    if (this.isTextEditable(entry.name)) {
      this.editFile(this.toFileRef(entry.path, entry.name));
      return;
    }
    // Everything else can only be opened by fetching it, which is a download.
    if (!this.canDownloadHere) {
      this.toast.info(
        'View only',
        `${this.shareCtx?.share.ownerUsername ?? 'The owner'} shared this without download access, so "${entry.name}" cannot be opened here.`,
      );
      return;
    }
    this.downloadFileByPath(entry.path, entry.name);
  }

  /** Leaves the shared area and returns to the user's own root. */
  exitSharedMode(): void {
    this.inSharedRoot = false;
    this.shareCtx = null;
    this.receivedShares = [];
    this.loadRootFolder();
  }

  /**
   * Loads who-has-access data: own shares grouped by path + the member list that
   * feeds both the avatar stack and the share dialog. Failures stay silent on
   * purpose — this is decoration around the file list, and an error banner over a
   * working cloud view would be misleading. The share dialog reports for itself.
   */
  private loadSharingMeta(): void {
    this.loadMembers().subscribe({ error: () => undefined });
    this.resourceShareService.listOwned().subscribe({
      next: (shares) => {
        this.sharesByPath.clear();
        shares
          .filter((s) => !s.revoked)
          .forEach((s) => {
            const list = this.sharesByPath.get(s.relativePath) ?? [];
            list.push(s);
            this.sharesByPath.set(s.relativePath, list);
          });
      },
      error: () => undefined,
    });
  }

  /**
   * Fetches the member list and derives both uses from it: the avatar lookup and
   * the recipient options of the share dialog. One source, so opening the dialog
   * does not request the same list a second time.
   */
  private loadMembers(): Observable<UserDto[]> {
    return this.userService.getAllUsers().pipe(
      tap((users) => {
        this.shareCandidates = users;
        this.avatarByUsername.clear();
        users.forEach((u) =>
          this.avatarByUsername.set(
            u.username,
            this.userService.getProfilePictureUrl(u.profilePicture),
          ),
        );
      }),
    );
  }

  /** Active shares (recipients) of an own folder, for the avatar stack. */
  recipientsFor(entry: CloudEntry): ResourceShareDto[] {
    if (this.sharedMode || entry.kind !== 'folder') {
      return [];
    }
    return this.sharesByPath.get(entry.path) ?? [];
  }

  avatarUrl(username: string): string | null {
    return this.avatarByUsername.get(username) ?? null;
  }

  /** Comma-separated recipients of an own folder, for the access tooltip. */
  accessNames(entry: CloudEntry): string {
    return this.recipientsFor(entry)
      .map((s) => s.recipientUsername)
      .join(', ');
  }

  initialOf(username: string): string {
    return (username || '?').charAt(0).toUpperCase();
  }

  /** Owner of a share row shown in the "Shared with me" list. */
  private shareOf(entry: CloudEntry): ResourceShareDto | undefined {
    if (!this.inSharedRoot) {
      return undefined;
    }
    const id = entry.path.slice(this.SHARE_ENTRY_PREFIX.length);
    return this.receivedShares.find((s) => s.id === id);
  }

  ownerOf(entry: CloudEntry): string | null {
    return this.shareOf(entry)?.ownerUsername ?? null;
  }

  /** Recipient removes a share given to them (leaves it). */
  confirmLeaveShare(entry: CloudEntry): void {
    const share = this.shareOf(entry);
    if (!share) {
      return;
    }
    this.confirmationService.confirm({
      header: 'Remove shared item',
      message: `"${share.displayName}" will disappear from your Shared with me. ${share.ownerUsername}'s files are not deleted, and they can share it again later.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Remove',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => {
        this.resourceShareService.leave(share.id).subscribe({
          next: () => {
            this.enterSharedRoot();
            this.toast.success(
              'Removed',
              `"${share.displayName}" is no longer shared with you.`,
            );
          },
          error: () =>
            this.toast.error('Could not remove', 'Please try again.'),
        });
      },
    });
  }

  /** Breadcrumb trail while in the shared area. */
  get sharedBreadcrumbItems(): MenuItem[] {
    const items: MenuItem[] = [
      { label: 'My Cloud', command: () => this.exitSharedMode() },
      { label: 'Shared with me', command: () => this.enterSharedRoot() },
    ];
    if (this.shareCtx) {
      const share = this.shareCtx.share;
      items.push({
        label: share.displayName,
        command: () => this.loadShareContent(''),
      });
      const parts = this.shareCtx.sub ? this.shareCtx.sub.split('/') : [];
      parts.forEach((label, index) => {
        const sub = parts.slice(0, index + 1).join('/');
        items.push({ label, command: () => this.loadShareContent(sub) });
      });
    }
    return items;
  }

  openShareWithMemberDialog(path: string, name: string) {
    this.selectedEntryForMemberShare = { path, name };
    this.shareRecipientUsername = '';
    this.shareAllowDownload = true;
    this.shareAllowEdit = false;
    this.showShareWithMemberDialog = true;

    // Already loaded with the rest of the sharing metadata; only fetch when that
    // did not get through, so the dialog is not held up by a redundant request.
    if (this.shareCandidates.length) {
      return;
    }
    this.loadMembers().subscribe({
      error: () =>
        this.toast.error(
          'Could not load members',
          'The member list is unavailable.',
        ),
    });
  }

  submitShareWithMember() {
    if (!this.selectedEntryForMemberShare || !this.shareRecipientUsername) {
      return;
    }
    // VIEW is implied by every share; the other two are opt-in.
    const permissions: SharePermission[] = ['VIEW'];
    if (this.shareAllowDownload) {
      permissions.push('DOWNLOAD');
    }
    if (this.shareAllowEdit) {
      permissions.push('EDIT');
    }

    const { path, name } = this.selectedEntryForMemberShare;
    const recipient = this.shareRecipientUsername;
    this.creatingMemberShare = true;

    this.resourceShareService
      .create(path, recipient, permissions)
      .pipe(finalize(() => (this.creatingMemberShare = false)))
      .subscribe({
        next: () => {
          this.showShareWithMemberDialog = false;
          this.loadSharingMeta();
          this.toast.success(
            'Shared',
            `"${name}" is now available to ${recipient}.`,
          );
        },
        error: (err: unknown) => {
          const status = (err as { status?: number })?.status;
          this.toast.error(
            'Share failed',
            status === 404
              ? 'That member does not exist.'
              : this.getErrorMessage(err) || 'Could not share this item.',
          );
        },
      });
  }

  ngOnDestroy(): void {
    // Invalidate in-flight callbacks and drop any pending poll timer.
    this.scanRunId++;
    this.stopScanPolling();
  }

  private stopScanPolling(): void {
    if (this.scanPollHandle) {
      clearTimeout(this.scanPollHandle);
      this.scanPollHandle = undefined;
    }
    this.scanJobId = undefined;
  }

  private isTerminalScan(status: string): boolean {
    return status === 'COMPLETED' || status === 'FAILED';
  }

  scanCurrentFolder(): void {
    const folder = this.currentFolder;
    this.scanFolderLabel =
      folder && folder.path !== this.rootPath
        ? this.getNameFromPath(folder.path)
        : 'your Cloud folder';

    this.stopScanPolling();
    const runId = ++this.scanRunId;
    this.showScanDialog = true;
    this.scanJob = undefined;
    this.scanError = undefined;
    this.scanRateLimitedPolls = 0;
    this.scanning = true;

    const relativePath = this.getRelativePath(folder?.path || this.rootPath);
    this.cloudService.startFolderScan(relativePath).subscribe({
      next: (job) => {
        // A closed dialog or a newer scan supersedes this start.
        if (runId !== this.scanRunId) return;
        this.scanJob = job;
        this.scanJobId = job.jobId;
        if (this.isTerminalScan(job.status)) {
          this.scanning = false;
        } else {
          this.pollScanJob(runId);
        }
      },
      error: (err) => {
        if (runId !== this.scanRunId) return;
        this.scanning = false;
        this.scanError = this.scanStartErrorMessage(err);
      },
    });
  }

  private pollScanJob(runId: number, delayMs = this.scanPollIntervalMs): void {
    this.scanPollHandle = setTimeout(() => {
      if (runId !== this.scanRunId) return;
      const jobId = this.scanJobId;
      if (!jobId) return;
      this.cloudService.getScanJob(jobId).subscribe({
        next: (job) => {
          if (runId !== this.scanRunId) return;
          this.scanRateLimitedPolls = 0;
          this.scanJob = job;
          if (this.isTerminalScan(job.status)) {
            this.scanning = false;
          } else {
            this.pollScanJob(runId);
          }
        },
        error: (err) => {
          if (runId !== this.scanRunId) return;
          const status = (err as { status?: number })?.status;
          if (status === 429 && this.canRetryRateLimitedPoll()) {
            // The scan is untouched by our polling being throttled: wait longer
            // and ask again rather than reporting a failure that didn't happen.
            this.pollScanJob(runId, this.scanRateLimitBackoffMs);
            return;
          }
          this.scanning = false;
          this.scanError = this.scanPollErrorMessage(err);
        },
      });
    }, delayMs);
  }

  private canRetryRateLimitedPoll(): boolean {
    this.scanRateLimitedPolls++;
    return this.scanRateLimitedPolls <= this.maxRateLimitedPolls;
  }

  onScanDialogHide(): void {
    // Fired by the dialog on any close (footer button, header X, or ESC);
    // invalidate the run so polling stops and late responses are ignored.
    this.scanRunId++;
    this.stopScanPolling();
    this.scanning = false;
  }

  private scanStartErrorMessage(err: unknown): string {
    const status = (err as { status?: number })?.status;
    if (status === 429) {
      return 'Too many scans were started recently. Please wait a moment and try again.';
    }
    if (status === 400) {
      return this.getErrorMessage(err);
    }
    return 'Could not start the scan. Please try again.';
  }

  private scanPollErrorMessage(err: unknown): string {
    const status = (err as { status?: number })?.status;
    if (status === 404) {
      return 'This scan is no longer available. Start a new scan to see results.';
    }
    if (status === 429) {
      // Only reached once the backoff above is exhausted; the scan itself may
      // well still be running, so don't tell the user it failed.
      return 'The server is busy, so we stopped checking on this scan. It may still be running — reopen the scan in a moment.';
    }
    return 'Lost track of the scan. Please try again.';
  }

  get scanInfectedFindings(): FileScanResultDto[] {
    return (this.scanJob?.findings ?? []).filter(
      (f) => f.verdict === 'INFECTED',
    );
  }

  get scanErroredFindings(): FileScanResultDto[] {
    return (this.scanJob?.findings ?? []).filter((f) => f.verdict === 'ERROR');
  }

  /**
   * True when a completed scan could not actually scan anything — every file
   * came back with an error verdict. This is how the backend surfaces a
   * disabled or unreachable scanner (each file yields an ERROR result), so the
   * UI can show a clear "unavailable" message instead of a wall of errors.
   */
  get scanScannerUnavailable(): boolean {
    const job = this.scanJob;
    if (!job || job.status !== 'COMPLETED' || job.filesScanned <= 0) {
      return false;
    }
    return (
      job.infectedCount === 0 &&
      this.scanErroredFindings.length === job.filesScanned
    );
  }

  get scanUnavailableMessage(): string {
    const disabled = this.scanErroredFindings.some((f) =>
      (f.detail ?? '').toLowerCase().includes('disabled'),
    );
    return disabled
      ? 'Virus scanning is turned off on the server, so nothing was scanned.'
      : 'The virus scanner is currently unavailable, so nothing was scanned. Please try again later.';
  }

  get scanNoThreats(): boolean {
    const job = this.scanJob;
    return (
      !!job &&
      job.status === 'COMPLETED' &&
      job.infectedCount === 0 &&
      !this.scanScannerUnavailable
    );
  }

  navigateToFolder(folderPath?: string) {
    this.searchActive = false;
    this.searchQuery = '';
    this.searching = false;
    this.loading = true;
    this.contentFirst = 0;
    // Invalidate any in-flight search/content request so a stale response
    // can't overwrite the entries while navigation is in progress.
    this.contentRequestId++;
    const relativePath = this.getRelativePath(folderPath || this.rootPath);
    this.cloudService.getFolderByPath(relativePath, false).subscribe({
      next: (folder) => {
        this.currentFolder = folder;
        this.updateBreadcrumbs(folder.path);
        this.loadFolderContent(relativePath, 0);
      },
      error: () => {
        this.error = 'Error navigating to folder';
        this.toast.error('Navigation failed', 'Could not open this folder.');
        this.loading = false;
      },
    });
  }

  navigateToRoot() {
    if (this.sharedMode) {
      this.exitSharedMode();
      return;
    }
    this.navigateToFolder(this.rootPath);
  }

  updateBreadcrumbs(currentPath: string) {
    this.breadcrumbs = [];
    const relativePath = currentPath
      .replace(this.rootPath, '')
      .replace(/^[\\/]/, '');
    if (!relativePath) return;

    const parts = relativePath.split(/[\\/]/);
    let accumulatedPath = this.rootPath;

    parts.forEach((part) => {
      accumulatedPath = accumulatedPath + '/' + part;
      this.breadcrumbs.push({ name: part, path: accumulatedPath });
    });
  }

  getRelativePath(fullPath: string): string {
    const normalizedPath = (fullPath || '').replace(/\\/g, '/').trim();
    const normalizedRoot = (this.rootPath || '').replace(/\\/g, '/').trim();

    if (!normalizedPath || normalizedPath === '/' || normalizedPath === '.') {
      return '/';
    }

    if (
      normalizedPath === normalizedRoot ||
      (normalizedRoot === '.' && normalizedPath === '.')
    ) {
      return '/';
    }

    const rootPrefix =
      normalizedRoot && normalizedRoot !== '.' && normalizedRoot !== '/'
        ? `${normalizedRoot}/`
        : '';

    let relative = normalizedPath;
    if (rootPrefix && relative.startsWith(rootPrefix)) {
      relative = relative.substring(rootPrefix.length);
    }

    relative = relative.replace(/^\/+/, '');
    return relative || '/';
  }

  toggleMenu(event: Event, menu: Menu) {
    menu.toggle(event);
  }

  openFileUploadDialog() {
    this.fileUploadInput?.nativeElement.click();
  }

  openCreateFolderDialog() {
    this.newFolderName = '';
    this.showCreateFolderDialog = true;
  }

  createNewFolder() {
    const folderName = this.newFolderName.trim();
    if (!folderName) return;
    if (this.shareCtx) {
      const ctx = this.shareCtx;
      this.resourceShareService
        .createFolder(ctx.share.id, ctx.sub, folderName)
        .subscribe({
          next: () => {
            this.showCreateFolderDialog = false;
            this.loadShareContent(ctx.sub);
            this.toast.success(
              'Folder created',
              `"${folderName}" was created.`,
            );
          },
          error: (err) =>
            this.toast.error('Create failed', this.getErrorMessage(err)),
        });
      return;
    }
    const currentPath = this.getRelativePath(this.currentFolder?.path || '/');
    this.cloudService.createFolder(currentPath, folderName).subscribe({
      next: () => {
        this.showCreateFolderDialog = false;
        this.navigateToFolder(this.currentFolder?.path);
        this.toast.success('Folder created', `"${folderName}" was created.`);
      },
      error: (err) =>
        this.toast.error('Create failed', this.getErrorMessage(err)),
    });
  }

  createNewFile() {
    this.editingFile = null;
    this.newFileName = '';
    this.fileContent = '';
    this.originalFileName = '';
    this.originalFileContent = '';
    this.outline = [];
    this.saveStatus = 'saved';
    this.editorMode = 'edit';
    this.previewHtml = '';
    this.showFileEditor = true;
  }

  private isTextEditable(fileName: string): boolean {
    const ext = fileName.split('.').pop()?.toLowerCase();
    const textExt = [
      'txt',
      'md',
      'json',
      'xml',
      'log',
      'csv',
      'ts',
      'js',
      'scss',
      'css',
      'html',
      'yml',
      'yaml',
      'java',
      'py',
      'sql',
    ];
    return !!ext && textExt.includes(ext);
  }

  onEditAction(path: string, name: string) {
    const file = this.toFileRef(path, name);
    if (this.isTextEditable(name)) {
      this.editFile(file);
      return;
    }
    this.openRenameFileDialog(file);
  }

  editFile(file: FileDto) {
    if (!this.isTextEditable(file.name)) {
      this.openRenameFileDialog(file);
      return;
    }

    this.editingFile = file;
    this.newFileName = file.name;
    this.originalFileName = file.name;

    const onContent = (content: string) => {
      this.originalFileName = file.name;
      this.originalFileContent = content;
      this.fileContent = content;
      this.saveStatus = 'saved';
      this.editorMode = 'edit';
      this.updatePreview();
      this.updateOutline();
      this.showFileEditor = true;
    };
    const onError = (err: unknown) => {
      this.editingFile = null;
      this.toast.error('Could not open file', this.getErrorMessage(err));
    };

    if (this.shareCtx) {
      // file.path is relative to the share root in shared mode. Read through the
      // view endpoint: opening a file is not downloading it, so this works on a
      // share that grants VIEW only.
      this.resourceShareService
        .viewFile(this.shareCtx.share.id, file.path)
        .subscribe({
          next: (blob) =>
            blob
              .text()
              .then(onContent)
              .catch(() => onError(null)),
          error: onError,
        });
      return;
    }

    const relativePath = this.getRelativePath(file.path);
    this.cloudService.getFileContent(relativePath).subscribe({
      next: onContent,
      error: onError,
    });
  }

  async saveFile() {
    const nameToSave = this.newFileName.trim();
    if (!nameToSave) return;

    // Inside a share: save through the share API (EDIT-gated). Saving an existing
    // file keeps its name — the upload would otherwise leave the original behind
    // and add a second file under the new name. The name field is disabled to
    // match; this is the guard for it.
    if (this.shareCtx) {
      const ctx = this.shareCtx;
      const savedName = this.editingFile ? this.editingFile.name : nameToSave;
      try {
        const fileBlob = new Blob([this.fileContent], { type: 'text/plain' });
        const file = new File([fileBlob], savedName);
        await firstValueFrom(
          this.resourceShareService.upload(ctx.share.id, ctx.sub, file),
        );
        this.originalFileContent = this.fileContent;
        this.originalFileName = savedName;
        this.loadShareContent(ctx.sub);
        this.closeFileEditor();
        this.toast.success(
          this.editingFile ? 'File updated' : 'File created',
          `"${savedName}" was saved.`,
        );
      } catch (err: unknown) {
        this.toast.error('Save failed', this.getErrorMessage(err));
      }
      return;
    }

    try {
      if (this.editingFile && nameToSave !== this.editingFile.name) {
        const relativeSource = this.getRelativePath(this.editingFile.path);
        const relativeTargetDir = this.getParentRelativePath(
          this.editingFile.path,
        );
        const relativeTarget = this.joinRelativePath(
          relativeTargetDir,
          nameToSave,
        );
        await firstValueFrom(
          this.cloudService.renameOrMoveFile(relativeSource, relativeTarget),
        );
      }

      const currentPath = this.getRelativePath(this.currentFolder?.path || '/');
      const fileBlob = new Blob([this.fileContent], { type: 'text/plain' });
      const file = new File([fileBlob], nameToSave);
      await firstValueFrom(this.cloudService.uploadFile(currentPath, file));

      this.newFileName = nameToSave;
      this.originalFileContent = this.fileContent;
      this.originalFileName = nameToSave;

      this.navigateToFolder(this.currentFolder?.path);
      this.closeFileEditor();
      this.toast.success(
        this.editingFile ? 'File updated' : 'File created',
        `"${nameToSave}" was saved.`,
      );
    } catch (err: unknown) {
      this.toast.error('Save failed', this.getErrorMessage(err));
    }
  }

  uploadFile(folderPath: string, file: File) {
    if (this.shareCtx) {
      const ctx = this.shareCtx;
      this.resourceShareService.upload(ctx.share.id, ctx.sub, file).subscribe({
        next: () => {
          this.loadShareContent(ctx.sub);
          this.toast.success(
            'Upload complete',
            `"${file.name}" uploaded successfully.`,
          );
        },
        error: (err) =>
          this.toast.error('Upload failed', this.getErrorMessage(err)),
      });
      return;
    }
    this.cloudService.uploadFile(folderPath, file).subscribe({
      next: () => {
        this.navigateToFolder(this.currentFolder?.path);
        this.closeFileEditor();
        this.toast.success(
          'Upload complete',
          `"${file.name}" uploaded successfully.`,
        );
      },
      error: (err) =>
        this.toast.error('Upload failed', this.getErrorMessage(err)),
    });
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const files = input?.files;

    if (!files || files.length === 0) return;

    const currentPath = this.getRelativePath(this.currentFolder?.path || '/');

    for (const file of Array.from(files)) {
      this.uploadFile(currentPath, file);
    }

    input.value = '';
  }
  onExternalDragOver(event: DragEvent) {
    if (this.draggedPath) return;

    const isFileDrag =
      !!event.dataTransfer &&
      Array.from(event.dataTransfer.types).includes('Files');

    if (!isFileDrag) {
      return;
    }

    event.preventDefault();
    this.isExternalDrag = true;
    event.dataTransfer!.dropEffect = 'copy';
  }
  onExternalDragLeave(event: DragEvent) {
    const currentTarget = event.currentTarget as HTMLElement;
    const relatedTarget = event.relatedTarget as Node | null;

    if (!relatedTarget || !currentTarget.contains(relatedTarget)) {
      this.isExternalDrag = false;
    }
  }

  onExternalDrop(event: DragEvent) {
    event.preventDefault();
    this.isExternalDrag = false;

    if (this.draggedPath) {
      return;
    }

    const files = event.dataTransfer?.files;

    if (!files || files.length === 0) {
      return;
    }

    const currentPath = this.getRelativePath(this.currentFolder?.path || '/');

    for (const file of Array.from(files)) {
      this.uploadFile(currentPath, file);
    }
  }

  confirmDeleteFolder(folderPath: string) {
    this.confirmationService.confirm({
      header: 'Delete Folder',
      message: 'Do you really want to delete this folder?',
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.deleteFolder(folderPath),
    });
  }

  deleteFolder(folderPath: string) {
    if (this.shareCtx) {
      this.deleteInShare(folderPath);
      return;
    }
    const relativePath = this.getRelativePath(folderPath);
    this.cloudService.deleteFolder(relativePath).subscribe({
      next: () => {
        this.navigateToFolder(this.currentFolder?.path);
        this.toast.success(
          'Folder deleted',
          `"${this.getNameFromPath(folderPath)}" removed.`,
        );
      },
      error: (err) =>
        this.toast.error('Delete failed', this.getErrorMessage(err)),
    });
  }
  /** Deletes an entry inside the current share (path is share-root relative). */
  private deleteInShare(path: string) {
    const ctx = this.shareCtx;
    if (!ctx) return;
    this.resourceShareService.deleteEntry(ctx.share.id, path).subscribe({
      next: () => {
        this.loadShareContent(ctx.sub);
        this.toast.success(
          'Deleted',
          `"${this.getNameFromPath(path)}" removed.`,
        );
      },
      error: (err) =>
        this.toast.error('Delete failed', this.getErrorMessage(err)),
    });
  }

  confirmDeleteFile(filePath: string) {
    const fileName = this.getNameFromPath(filePath);
    this.confirmationService.confirm({
      header: 'Delete File',
      message: `Do you really want to delete "${fileName}"?`,
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.deleteFile(filePath),
    });
  }

  deleteFile(filePath: string) {
    if (this.shareCtx) {
      this.deleteInShare(filePath);
      return;
    }
    const relativePath = this.getRelativePath(filePath);
    this.cloudService.deleteFile(relativePath).subscribe({
      next: () => {
        this.navigateToFolder(this.currentFolder?.path);
        this.toast.success(
          'File deleted',
          `"${this.getNameFromPath(filePath)}" removed.`,
        );
      },
      error: (err) =>
        this.toast.error('Delete failed', this.getErrorMessage(err)),
    });
  }

  openRenameFolderDialog(folderPath: string, folderName: string) {
    this.selectedFolderPathForRename = folderPath;
    this.selectedFolderNameForRename = folderName;
    this.renameFolderName = folderName;
    this.showRenameFolderDialog = true;
  }

  renameFolder() {
    const folderPath = this.selectedFolderPathForRename;
    const folderName = this.selectedFolderNameForRename;
    const newName = this.renameFolderName.trim();
    if (!folderPath || !folderName || !newName || newName === folderName)
      return;

    if (this.shareCtx) {
      const ctx = this.shareCtx;
      this.resourceShareService
        .rename(ctx.share.id, folderPath, newName)
        .subscribe({
          next: () => {
            this.showRenameFolderDialog = false;
            this.selectedFolderPathForRename = null;
            this.selectedFolderNameForRename = null;
            this.loadShareContent(ctx.sub);
            this.toast.success('Folder renamed', `Now named "${newName}".`);
          },
          error: (err) =>
            this.toast.error('Rename failed', this.getErrorMessage(err)),
        });
      return;
    }

    const relativeSource = this.getRelativePath(folderPath);
    const relativeTargetDir = this.getParentRelativePath(folderPath);
    const relativeTarget = this.joinRelativePath(relativeTargetDir, newName);

    this.cloudService
      .renameOrMoveFolder(relativeSource, relativeTarget)
      .subscribe({
        next: () => {
          this.showRenameFolderDialog = false;
          this.selectedFolderPathForRename = null;
          this.selectedFolderNameForRename = null;
          this.navigateToFolder(this.currentFolder?.path);
          this.toast.success('Folder renamed', `Now named "${newName}".`);
        },
        error: (err) =>
          this.toast.error('Rename failed', this.getErrorMessage(err)),
      });
  }

  openRenameFileDialog(file: FileDto) {
    this.selectedFileForRename = file;
    this.renameFileName = file.name;
    this.showRenameFileDialog = true;
  }

  renameFile() {
    const file = this.selectedFileForRename;
    const newName = this.renameFileName.trim();
    if (!file || !newName || newName === file.name) return;

    if (this.shareCtx) {
      const ctx = this.shareCtx;
      this.resourceShareService
        .rename(ctx.share.id, file.path, newName)
        .subscribe({
          next: () => {
            this.showRenameFileDialog = false;
            this.selectedFileForRename = null;
            this.loadShareContent(ctx.sub);
            this.toast.success('File renamed', `Now named "${newName}".`);
          },
          error: (err) =>
            this.toast.error('Rename failed', this.getErrorMessage(err)),
        });
      return;
    }

    const relativeSource = this.getRelativePath(file.path);
    const relativeTargetDir = this.getParentRelativePath(file.path);
    const relativeTarget = this.joinRelativePath(relativeTargetDir, newName);

    this.cloudService
      .renameOrMoveFile(relativeSource, relativeTarget)
      .subscribe({
        next: () => {
          this.showRenameFileDialog = false;
          this.selectedFileForRename = null;
          this.navigateToFolder(this.currentFolder?.path);
          this.toast.success('File renamed', `Now named "${newName}".`);
        },
        error: (err) =>
          this.toast.error('Rename failed', this.getErrorMessage(err)),
      });
  }

  /**
   * Hands a downloaded blob to the browser. The anchor has to be in the document
   * for Firefox to act on the click, and the object URL has to outlive it:
   * revoking in the same tick cuts larger downloads off, which for a folder
   * archive means a truncated, unreadable ZIP.
   */
  private saveBlob(blob: Blob, fileName: string): void {
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => window.URL.revokeObjectURL(url), 60_000);
  }

  downloadFile(file: FileDto) {
    const pathKey = file.path;
    this.downloadingPaths.add(pathKey);
    const blob$ = this.shareCtx
      ? this.resourceShareService.downloadFile(
          this.shareCtx.share.id,
          file.path,
        )
      : this.cloudService.getFileBlob(this.getRelativePath(file.path));
    blob$
      .pipe(
        finalize(() => {
          this.downloadingPaths.delete(pathKey);
        }),
      )
      .subscribe({
        next: (blob) => {
          this.saveBlob(blob, file.name);
          this.toast.info('Download started', `"${file.name}" is downloading.`);
        },
        error: (err) =>
          this.toast.error('Download failed', this.getErrorMessage(err)),
      });
  }

  isDownloading(path: string): boolean {
    return this.downloadingPaths.has(path);
  }

  private toFileRef(path: string, name: string): FileDto {
    return { path, name, size: 0, mimeType: '' };
  }

  downloadFileByPath(path: string, name: string) {
    this.downloadFile(this.toFileRef(path, name));
  }

  /**
   * Downloads a folder as a ZIP. Inside a share the archive has to come from the
   * share API: the path is relative to the shared folder, and the owner's
   * storage is not the recipient's to read from.
   */
  downloadFolder(folderPath: string, folderName: string) {
    const pathKey = folderPath;
    const ctx = this.shareCtx;
    const archive$ = ctx
      ? this.resourceShareService.downloadFolder(ctx.share.id, folderPath)
      : this.cloudService.getFolderArchive(this.getRelativePath(folderPath));
    this.downloadingPaths.add(pathKey);

    archive$
      .pipe(
        finalize(() => {
          this.downloadingPaths.delete(pathKey);
        }),
      )
      .subscribe({
        next: (blob) => {
          const zipName = folderName ? `${folderName}.zip` : 'archive.zip';
          this.saveBlob(blob, zipName);
          this.toast.info('Download started', `"${zipName}" is downloading.`);
        },
        error: (err) => {
          this.parseBlobError(err).subscribe((msg) => {
            this.toast.error('Download failed', msg);
          });
        },
      });
  }

  downloadCurrentFolder() {
    if (this.currentFolder) {
      const folderName =
        this.currentFolder.path === this.rootPath
          ? 'cloud-root'
          : this.getNameFromPath(this.currentFolder.path);
      this.downloadFolder(this.currentFolder.path, folderName);
    }
  }

  private parseBlobError(err: unknown): Observable<string> {
    const candidate = err as { error?: unknown };
    if (candidate && candidate.error instanceof Blob) {
      const reader = new FileReader();
      return new Observable<string>((observer) => {
        reader.onload = () => {
          try {
            const parsed = JSON.parse(reader.result as string);
            observer.next(
              parsed.message || 'Request failed. Please try again.',
            );
          } catch {
            observer.next('Request failed. Please try again.');
          }
          observer.complete();
        };
        reader.onerror = () => {
          observer.next('Request failed. Please try again.');
          observer.complete();
        };
        reader.readAsText(candidate.error as Blob);
      });
    }
    return of(this.getErrorMessage(err));
  }

  previewFileByPath(path: string, name: string) {
    this.previewFile(this.toFileRef(path, name));
  }

  updateOutline() {
    const lines = (this.fileContent || '').split('\n');
    const list: { text: string; level: number }[] = [];
    lines.forEach((line) => {
      const match = line.match(/^(#{1,6})\s+(.+)$/);
      if (match) {
        list.push({
          level: match[1].length,
          text: match[2].trim(),
        });
      }
    });
    this.outline = list;
  }

  scrollToHeading(index: number) {
    const container = document.querySelector('.markdown-preview');
    if (!container) return;
    const headings = container.querySelectorAll('h1, h2, h3, h4, h5, h6');
    if (headings && headings[index]) {
      headings[index].scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  resolveWikilink(targetName: string): FileDto | null {
    const normTarget = targetName.trim().toLowerCase().replace(/\.md$/, '');
    const matchedEntry = this.entries.find(
      (e) =>
        e.kind === 'file' &&
        (e.name.toLowerCase() === normTarget + '.md' ||
          e.name.toLowerCase() === normTarget),
    );
    if (matchedEntry) {
      return this.toFileRef(matchedEntry.path, matchedEntry.name);
    }
    return null;
  }

  handlePreviewClick(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (target && target.classList.contains('wikilink')) {
      event.preventDefault();
      const targetName = target.getAttribute('data-target');
      if (targetName) {
        const fileRef = this.resolveWikilink(targetName);
        if (fileRef) {
          this.editFile(fileRef);
        } else {
          this.toast.error(
            'File not found',
            `Could not find a Markdown file named "${targetName}" in the current folder.`,
          );
        }
      }
    }
  }

  onContentChange() {
    this.saveStatus = this.isEditorDirty ? 'unsaved' : 'saved';
    if (this.isMarkdownFile(this.newFileName)) {
      this.updateOutline();
      this.updatePreview();
    }
  }

  closeFileEditor() {
    this.showFileEditor = false;
    this.editingFile = null;
    this.newFileName = '';
    this.fileContent = '';
    this.originalFileName = '';
    this.originalFileContent = '';
    this.outline = [];
    this.saveStatus = 'saved';
    this.editorMode = 'edit';
    this.previewHtml = '';
  }

  canDeactivate(): Promise<boolean> | boolean {
    if (this.isEditorDirty) {
      return new Promise<boolean>((resolve) => {
        this.confirmationService.confirm({
          header: 'Unsaved changes',
          message: 'Exit without saving? Your changes will be lost.',
          icon: 'pi pi-exclamation-triangle',
          acceptLabel: 'Discard',
          rejectLabel: 'Keep editing',
          acceptButtonStyleClass: 'p-button-danger p-button-sm',
          rejectButtonStyleClass: 'p-button-text p-button-sm',
          accept: () => {
            this.closeFileEditor();
            resolve(true);
          },
          reject: () => {
            resolve(false);
          },
        });
      });
    }
    return true;
  }

  /** True while the editor is open with edits that have not been saved. */
  get isEditorDirty(): boolean {
    return this.showFileEditor && this.hasUnsavedEditorChanges();
  }

  private hasUnsavedEditorChanges(): boolean {
    return (
      this.fileContent !== this.originalFileContent ||
      this.newFileName !== this.originalFileName
    );
  }

  /**
   * Close the editor, but if there are unsaved edits ask first. Used by the
   * Cancel button and by the dialog's dismiss paths so no exit silently drops
   * changes.
   */
  requestCloseFileEditor(): void {
    if (!this.hasUnsavedEditorChanges()) {
      this.closeFileEditor();
      return;
    }
    this.confirmationService.confirm({
      header: 'Unsaved changes',
      message: 'Exit without saving? Your changes will be lost.',
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Discard',
      rejectLabel: 'Keep editing',
      acceptButtonStyleClass: 'p-button-danger p-button-sm',
      rejectButtonStyleClass: 'p-button-text p-button-sm',
      accept: () => this.closeFileEditor(),
    });
  }

  /**
   * The dialog was dismissed (the header close button, Esc, or a mask click),
   * which the two-way visible binding has already flipped off. Reverse it and
   * route through the same guard so a dismiss can't bypass the prompt.
   */
  onFileEditorHide(): void {
    if (!this.hasUnsavedEditorChanges()) {
      this.closeFileEditor();
      return;
    }
    this.showFileEditor = true;
    this.requestCloseFileEditor();
  }

  /** Native guard for a full-page leave (reload, tab close, external navigation). */
  @HostListener('window:beforeunload', ['$event'])
  warnBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.isEditorDirty) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  openCreateShareDialog(filePath: string, fileName: string, isFolder = false) {
    this.selectedFileForShare = { path: filePath, name: fileName };
    this.selectedShareIsFolder = isFolder;
    this.shareExpiryMinutes = 1440;
    this.sharePassword = '';
    this.showCreateShareDialog = true;
  }

  submitCreateShareLink() {
    if (!this.selectedFileForShare) return;

    this.creatingShareLink = true;
    const { path, name } = this.selectedFileForShare;
    const expiryMinutes = Number(this.shareExpiryMinutes);
    const neverExpires = expiryMinutes === 0;

    this.cloudService
      .createSecureSendLink(
        path,
        neverExpires ? null : expiryMinutes,
        this.sharePassword,
      )
      .pipe(finalize(() => (this.creatingShareLink = false)))
      .subscribe({
        next: (link) => {
          this.createdShareUrl = link.shareUrl || '';
          this.showCreateShareDialog = false;
          this.showGeneratedLinkDialog = true;
          this.toast.success(
            'Share link created',
            `Link for "${name}" created successfully.`,
          );
        },
        error: (err: unknown) => {
          const status = (err as { status?: number })?.status;
          if (status === 429) {
            this.toast.error(
              'Rate limit reached',
              'Too many share links created. Please wait before trying again.',
            );
          } else {
            this.toast.error(
              'Share failed',
              this.getErrorMessage(err) || 'Could not create share link.',
            );
          }
        },
      });
  }

  copyShareUrlToClipboard() {
    if (!this.createdShareUrl) return;
    navigator.clipboard.writeText(this.createdShareUrl).then(
      () => {
        this.toast.success('Copied!', 'Share link copied to clipboard.');
      },
      () => {
        this.toast.error('Copy failed', 'Please manually copy the URL.');
      },
    );
  }

  isMarkdownFile(fileName: string): boolean {
    if (!fileName) return false;
    const ext = fileName.split('.').pop()?.toLowerCase();
    return ext === 'md' || ext === 'markdown';
  }

  private escapeHtml(str: string): string {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  updatePreview() {
    if (!this.isMarkdownFile(this.newFileName)) return;
    let rawMarkdown = this.fileContent || '';

    // Convert [[wikilink]] to clickable links with escaped target and label
    rawMarkdown = rawMarkdown.replace(/\[\[([^\]]+)\]\]/g, (_, match) => {
      const parts = match.split('|');
      const target = parts[0].trim();
      const label = (parts[1] || target).trim();
      const escapedTarget = this.escapeHtml(target);
      const escapedLabel = this.escapeHtml(label);
      return `<a class="wikilink cursor-pointer text-primary hover:underline" data-target="${escapedTarget}">${escapedLabel}</a>`;
    });

    try {
      // marked runs synchronously here, so the result is always a string.
      const dirtyHtml = marked.parse(rawMarkdown, { async: false });
      // DOMPurify strips any XSS payload; allow data-target attributes
      const cleanHtml = DOMPurify.sanitize(dirtyHtml, {
        ADD_ATTR: ['data-target'],
      });
      this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(cleanHtml);
    } catch (e) {
      console.error('Error rendering markdown', e);
      this.previewHtml = '';
    }
  }

  setEditorMode(mode: 'edit' | 'preview' | 'split') {
    this.editorMode = mode;
    if (mode === 'preview' || mode === 'split') {
      this.updatePreview();
    }
  }

  onFileNameChange() {
    if (this.isMarkdownFile(this.newFileName)) {
      this.updatePreview();
    } else {
      this.editorMode = 'edit';
    }
  }

  getParentRelativePath(fullPath: string): string {
    const relative = this.getRelativePath(fullPath);
    if (!relative || relative === '/') return '/';
    const lastSlash = relative.lastIndexOf('/');
    if (lastSlash <= 0) return '/';
    return relative.substring(0, lastSlash);
  }

  private joinRelativePath(parentPath: string, name: string): string {
    if (!parentPath || parentPath === '/') return name;
    return `${parentPath}/${name}`;
  }

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  onDragStart(event: DragEvent, path: string, isFolder: boolean) {
    this.draggedPath = path;
    this.draggedIsFolder = isFolder;
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', path);
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  isInvalidMove(
    sourcePath: string,
    targetPath: string,
    isFolder: boolean,
  ): boolean {
    const relativeSource = this.getRelativePath(sourcePath);
    const relativeTarget = this.getRelativePath(targetPath);

    // Cannot move to the exact same path
    if (relativeSource === relativeTarget) {
      return true;
    }

    // Cannot move a folder into its own subfolder
    if (isFolder) {
      if (relativeTarget.startsWith(relativeSource + '/')) {
        return true;
      }
    }

    // Cannot move an item to its current parent folder (it's already there)
    const currentParent = this.getParentRelativePath(sourcePath);
    if (currentParent === relativeTarget) {
      return true;
    }

    return false;
  }

  onDragOver(event: DragEvent, path: string, isFolder: boolean) {
    if (!this.draggedPath) return;

    if (
      isFolder &&
      !this.isInvalidMove(this.draggedPath, path, this.draggedIsFolder)
    ) {
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
      this.draggedOverPath = path;
    }
  }

  onDragLeave() {
    this.draggedOverPath = null;
  }

  onDragEnd() {
    this.draggedPath = null;
    this.draggedOverPath = null;
  }

  async onDrop(event: DragEvent, targetFolderPath?: string | null) {
    event.preventDefault();
    this.draggedOverPath = null;
    if (!this.draggedPath) return;

    const targetPath = targetFolderPath || this.currentFolder?.path;
    if (!targetPath) return;

    if (
      this.isInvalidMove(this.draggedPath, targetPath, this.draggedIsFolder)
    ) {
      if (
        this.draggedIsFolder &&
        (this.getRelativePath(targetPath) ===
          this.getRelativePath(this.draggedPath) ||
          this.getRelativePath(targetPath).startsWith(
            this.getRelativePath(this.draggedPath) + '/',
          ))
      ) {
        this.toast.error(
          'Invalid move',
          'Cannot move a folder into itself or its own subfolder.',
        );
      }
      this.draggedPath = null;
      return;
    }

    const relativeSource = this.getRelativePath(this.draggedPath);
    const relativeTarget = this.getRelativePath(targetPath);

    try {
      if (this.draggedIsFolder) {
        await firstValueFrom(
          this.cloudService.renameOrMoveFolder(
            relativeSource,
            this.joinRelativePath(
              relativeTarget,
              this.getNameFromPath(this.draggedPath),
            ),
          ),
        );
      } else {
        await firstValueFrom(
          this.cloudService.renameOrMoveFile(
            relativeSource,
            this.joinRelativePath(
              relativeTarget,
              this.getNameFromPath(this.draggedPath),
            ),
          ),
        );
      }
      this.reloadCurrentFolder();
      this.toast.success('Item moved', 'Move completed successfully.');
    } catch (err: unknown) {
      this.toast.error('Move failed', this.getErrorMessage(err));
    } finally {
      this.draggedPath = null;
    }
  }

  onBreadcrumbDragOver(event: DragEvent, path?: string) {
    if (!this.draggedPath || !path) return;

    if (!this.isInvalidMove(this.draggedPath, path, this.draggedIsFolder)) {
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
      this.draggedOverPath = path;
    }
  }

  async onBreadcrumbDrop(event: DragEvent, targetPath?: string) {
    event.preventDefault();
    this.draggedOverPath = null;
    if (!this.draggedPath || !targetPath) return;

    if (
      this.isInvalidMove(this.draggedPath, targetPath, this.draggedIsFolder)
    ) {
      this.draggedPath = null;
      return;
    }

    const relativeSource = this.getRelativePath(this.draggedPath);
    const relativeTarget = this.getRelativePath(targetPath);

    try {
      if (this.draggedIsFolder) {
        await firstValueFrom(
          this.cloudService.renameOrMoveFolder(
            relativeSource,
            this.joinRelativePath(
              relativeTarget,
              this.getNameFromPath(this.draggedPath),
            ),
          ),
        );
      } else {
        await firstValueFrom(
          this.cloudService.renameOrMoveFile(
            relativeSource,
            this.joinRelativePath(
              relativeTarget,
              this.getNameFromPath(this.draggedPath),
            ),
          ),
        );
      }
      this.reloadCurrentFolder();
      this.toast.success('Item moved', 'Move completed successfully.');
    } catch (err: unknown) {
      this.toast.error('Move failed', this.getErrorMessage(err));
    } finally {
      this.draggedPath = null;
    }
  }

  getNameFromPath(path: string): string {
    const normalized = path.replace(/\\/g, '/');
    const parts = normalized.split('/');
    return parts[parts.length - 1];
  }

  reloadCurrentFolder() {
    if (this.currentFolder) {
      this.navigateToFolder(this.currentFolder.path);
    } else {
      this.loadRootFolder();
    }
  }

  previewFile(file: FileDto) {
    const ext = file.name.split('.').pop()?.toLowerCase();
    const imageExt = ['png', 'jpg', 'jpeg', 'gif', 'bmp'];
    const pdfExt = ['pdf'];
    const textExt = ['txt', 'md', 'json', 'xml', 'log'];

    if (ext && (imageExt.includes(ext) || pdfExt.includes(ext))) {
      const relativePath = this.getRelativePath(file.path);
      this.cloudService.getFileView(relativePath).subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          window.open(url, '_blank');
        },
        error: (err) =>
          this.toast.error('Preview failed', this.getErrorMessage(err)),
      });
    } else if (ext && textExt.includes(ext)) {
      this.editFile(file);
    } else {
      this.downloadFile(file);
    }
  }

  openChecksumDialog(file: { path: string; name: string }): void {
    if (!file) return;
    const requestId = ++this.checksumRequestId;
    this.selectedFileForChecksum = { name: file.name, path: file.path };
    this.showChecksumDialog = true;
    this.checksumLoading = true;
    this.checksumError = undefined;
    this.checksumResult = undefined;
    this.expectedHash = '';
    this.copiedHashState = false;

    const relativePath = this.getRelativePath(file.path);
    this.cloudService.getFileChecksum(relativePath).subscribe({
      next: (result) => {
        if (requestId !== this.checksumRequestId || !this.showChecksumDialog)
          return;
        this.checksumResult = result;
        this.checksumLoading = false;
      },
      error: (err) => {
        if (requestId !== this.checksumRequestId || !this.showChecksumDialog)
          return;
        this.checksumLoading = false;
        const msg = this.getErrorMessage(err);
        this.checksumError = msg;
        this.toast.error('Checksum Calculation Failed', msg);
      },
    });
  }

  onChecksumDialogHide(): void {
    this.checksumRequestId++;
    this.showChecksumDialog = false;
    this.checksumLoading = false;
    this.checksumError = undefined;
    this.checksumResult = undefined;
    this.selectedFileForChecksum = null;
    this.expectedHash = '';
    this.copiedHashState = false;
  }

  copyChecksumToClipboard(): void {
    if (!this.checksumResult?.checksum) return;
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      navigator.clipboard
        .writeText(this.checksumResult.checksum)
        .then(() => {
          this.copiedHashState = true;
          this.toast.success(
            'Checksum Copied',
            'SHA-256 checksum copied to clipboard.',
          );
          setTimeout(() => {
            this.copiedHashState = false;
          }, 2000);
        })
        .catch(() => {
          this.toast.error(
            'Copy Failed',
            'Failed to copy checksum to clipboard.',
          );
        });
    } else {
      this.toast.error('Copy Failed', 'Clipboard API not supported.');
    }
  }

  get hashMatchStatus(): 'empty' | 'match' | 'mismatch' {
    if (!this.expectedHash || !this.expectedHash.trim()) return 'empty';
    if (!this.checksumResult?.checksum) return 'empty';
    const expected = this.expectedHash.trim().toLowerCase();
    const computed = this.checksumResult.checksum.trim().toLowerCase();
    return expected === computed ? 'match' : 'mismatch';
  }
}
