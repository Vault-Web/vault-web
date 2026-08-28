import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';
import { FolderContentItemDto } from '../../models/dtos/FolderContentItemDto';
import { PublicShareDto } from '../../models/dtos/SecureSendLinkDto';
import { PublicShareService } from '../../services/public-share.service';

/** How a previewed file is rendered; anything else is offered as a download. */
type PreviewKind = 'image' | 'text' | 'pdf';

/** How long a download's object URL is kept alive after the click. */
const OBJECT_URL_LIFETIME_MS = 60_000;

/** Kept in step with what the cloud view previews and opens in its editor. */
const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'bmp'];
const TEXT_EXTENSIONS = [
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

interface Preview {
  name: string;
  kind: PreviewKind;
  /** Object URL of an image. A blob: URL passes Angular's URL sanitizer as it is. */
  imageUrl?: string;
  /** The same object URL, trusted for the frame a PDF is rendered in. */
  pdfUrl?: SafeResourceUrl;
  /** Decoded content, for text previews. */
  text?: string;
}

/**
 * Landing page a share link points at. Recipients have no account, so this page never talks to an
 * authenticated endpoint and never redirects to the login screen.
 *
 * <p>A file link describes the file and downloads it. A folder link opens a browser instead: the
 * recipient walks the tree, previews or downloads single files, and can take the current folder as
 * a ZIP. The password, where there is one, is held in memory and sent with every request — an
 * anonymous recipient has no session to keep it in.
 */
@Component({
  selector: 'app-share',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    TableModule,
    TooltipModule,
  ],
  templateUrl: './share.component.html',
  styleUrl: './share.component.scss',
})
export class ShareComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly publicShareService = inject(PublicShareService);
  private readonly sanitizer = inject(DomSanitizer);

  share: PublicShareDto | null = null;
  loading = true;
  unavailable = false;
  downloading = false;
  password = '';
  passwordError = '';
  downloadError = '';

  /** Folder link state. `opened` means a listing was accepted, password and all. */
  opened = false;
  browsing = false;
  entries: FolderContentItemDto[] = [];
  path = '';
  downloadingPath: string | null = null;
  archiving = false;

  /**
   * Two-way bound to the dialog. Deriving its visibility from `preview` instead
   * left the dialog's own close button fighting the binding, which put it
   * straight back on screen.
   */
  showPreview = false;
  preview: Preview | null = null;
  previewLoading = false;
  private previewObjectUrl: string | null = null;

  /** Counts listings so a late response can tell it has been overtaken. */
  private listingId = 0;
  private token = '';

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    if (!this.token) {
      this.loading = false;
      this.unavailable = true;
      return;
    }

    this.publicShareService
      .describe(this.token)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (share) => {
          this.share = share;
          // An unprotected folder needs no interaction before it can be shown.
          if (this.isFolder && !share.passwordProtected) {
            this.openFolder('');
          }
        },
        error: () => (this.unavailable = true),
      });
  }

  ngOnDestroy(): void {
    this.releasePreviewUrl();
  }

  get isFolder(): boolean {
    return this.share?.resourceType === 'FOLDER';
  }

  /** Trail of the current position inside the shared folder, its root first. */
  get breadcrumbs(): { label: string; path: string }[] {
    const parts = this.path ? this.path.split('/') : [];
    return [
      { label: this.share?.fileName ?? 'Shared folder', path: '' },
      ...parts.map((label, index) => ({
        label,
        path: parts.slice(0, index + 1).join('/'),
      })),
    ];
  }

  /** Opens the folder link, or a folder inside it. Doubles as the password check. */
  openFolder(path: string): void {
    // Clicking through folders faster than the server answers must not let an
    // earlier, slower listing overwrite the folder now on screen.
    const requestId = ++this.listingId;
    this.browsing = true;
    this.downloadError = '';
    this.publicShareService
      .content(this.token, path, this.passwordOrUndefined)
      .pipe(finalize(() => (this.browsing = false)))
      .subscribe({
        next: (entries) => {
          if (requestId !== this.listingId) {
            return;
          }
          this.entries = entries;
          this.path = path;
          this.opened = true;
          this.passwordError = '';
        },
        error: (error: HttpErrorResponse) => {
          if (requestId === this.listingId) {
            this.handleBrowseError(error);
          }
        },
      });
  }

  onEntryOpen(entry: FolderContentItemDto): void {
    if (entry.directory) {
      this.openFolder(entry.path);
      return;
    }
    const kind = this.previewKind(entry);
    if (kind) {
      this.openPreview(entry, kind);
      return;
    }
    this.downloadEntry(entry);
  }

  downloadEntry(entry: FolderContentItemDto): void {
    if (this.downloadingPath) {
      return;
    }
    if (!this.needsPasswordHeader) {
      this.streamFromBrowser(entry.path, entry.name, false);
      return;
    }
    this.downloadingPath = entry.path;
    this.downloadError = '';
    this.publicShareService
      .download(this.token, this.passwordOrUndefined, entry.path)
      .pipe(finalize(() => (this.downloadingPath = null)))
      .subscribe({
        next: (response) => this.saveBlob(response.body, entry.name),
        error: (error: HttpErrorResponse) => this.handleDownloadError(error),
      });
  }

  /** Downloads the folder currently being viewed, with everything below it. */
  downloadCurrentFolder(): void {
    if (this.archiving) {
      return;
    }
    const name = `${this.breadcrumbs[this.breadcrumbs.length - 1].label}.zip`;
    if (!this.needsPasswordHeader) {
      this.streamFromBrowser(this.path, name, true);
      return;
    }
    this.archiving = true;
    this.downloadError = '';
    this.publicShareService
      .downloadFolder(this.token, this.path, this.passwordOrUndefined)
      .pipe(finalize(() => (this.archiving = false)))
      .subscribe({
        next: (response) => this.saveBlob(response.body, name),
        error: (error: HttpErrorResponse) => this.handleDownloadError(error),
      });
  }

  openPreview(entry: FolderContentItemDto, kind: PreviewKind): void {
    this.releasePreviewUrl();
    this.previewLoading = true;
    this.preview = { name: entry.name, kind };
    this.showPreview = true;
    this.publicShareService
      .view(this.token, entry.path, this.passwordOrUndefined)
      .pipe(finalize(() => (this.previewLoading = false)))
      .subscribe({
        next: (blob) => this.renderPreview(blob, entry.name, kind),
        error: (error: HttpErrorResponse) => {
          this.closePreview();
          this.handleDownloadError(error);
        },
      });
  }

  closePreview(): void {
    this.releasePreviewUrl();
    this.preview = null;
    this.showPreview = false;
  }

  /** Downloads what is currently being previewed, straight from the preview. */
  downloadPreviewed(): void {
    const previewed = this.entries.find(
      (entry) => entry.name === this.preview?.name,
    );
    if (previewed) {
      this.downloadEntry(previewed);
    }
  }

  download(): void {
    if (this.downloading) {
      return;
    }
    if (this.share?.passwordProtected && !this.password.trim()) {
      this.passwordError = 'Please enter the password for this link.';
      return;
    }
    if (!this.needsPasswordHeader) {
      this.streamFromBrowser('', this.share?.fileName ?? 'download', false);
      return;
    }

    this.downloading = true;
    this.passwordError = '';
    this.downloadError = '';

    this.publicShareService
      .download(this.token, this.passwordOrUndefined)
      .pipe(finalize(() => (this.downloading = false)))
      .subscribe({
        next: (response) => this.saveBlob(response.body, this.share?.fileName),
        error: (error: HttpErrorResponse) => this.handleDownloadError(error),
      });
  }

  /** Password gate of a folder link: unlocking it is simply the first listing. */
  unlockFolder(): void {
    if (!this.password.trim()) {
      this.passwordError = 'Please enter the password for this link.';
      return;
    }
    this.openFolder('');
  }

  formatSize(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) {
      return '';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
  }

  /** The same two icons the cloud table uses, so a row reads the same everywhere. */
  iconFor(entry: FolderContentItemDto): string {
    return entry.directory ? 'pi-folder' : 'pi-file';
  }

  private get passwordOrUndefined(): string | undefined {
    return this.password.trim() || undefined;
  }

  /**
   * What a file can be shown as in place, or null when it can only be downloaded.
   * The extension lists are the ones the cloud view previews and edits by, so a
   * file that opens there opens here too.
   */
  private previewKind(entry: FolderContentItemDto): PreviewKind | null {
    const ext = entry.name.split('.').pop()?.toLowerCase() ?? '';
    if (IMAGE_EXTENSIONS.includes(ext)) {
      return 'image';
    }
    if (ext === 'pdf') {
      return 'pdf';
    }
    return TEXT_EXTENSIONS.includes(ext) ? 'text' : null;
  }

  private renderPreview(blob: Blob, name: string, kind: PreviewKind): void {
    if (kind === 'text') {
      blob
        .text()
        .then((text) => (this.preview = { name, kind, text }))
        .catch(() => this.closePreview());
      return;
    }
    this.previewObjectUrl = URL.createObjectURL(blob);
    this.preview =
      kind === 'image'
        ? { name, kind, imageUrl: this.previewObjectUrl }
        : {
            name,
            kind,
            pdfUrl: this.sanitizer.bypassSecurityTrustResourceUrl(
              this.previewObjectUrl,
            ),
          };
  }

  private releasePreviewUrl(): void {
    if (this.previewObjectUrl) {
      URL.revokeObjectURL(this.previewObjectUrl);
      this.previewObjectUrl = null;
    }
  }

  /** True while the link needs its password sent along, which only XHR can do. */
  private get needsPasswordHeader(): boolean {
    return !!this.share?.passwordProtected;
  }

  /**
   * Hands the download to the browser. It streams straight to disk, shows its own
   * progress and survives any size — a folder ZIP runs to hundreds of megabytes,
   * and pulling that through XHR into an in-memory blob is what made large
   * archives arrive empty.
   */
  private streamFromBrowser(path: string, fileName: string, folder: boolean) {
    this.downloadError = '';
    this.triggerDownload(
      this.publicShareService.downloadUrl(this.token, path, folder),
      fileName,
    );
  }

  private saveBlob(body: Blob | null, fileName?: string): void {
    if (!body) {
      this.downloadError = 'The download came back empty. Please try again.';
      return;
    }
    const url = URL.createObjectURL(body);
    this.triggerDownload(url, fileName ?? this.share?.fileName ?? 'download');
    // Revoking in the same tick cuts the download off before the browser has read
    // the blob, which is what larger files ran into. Release it once it can only
    // be finished or abandoned.
    setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_LIFETIME_MS);
  }

  private triggerDownload(url: string, fileName: string): void {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.rel = 'noopener';
    // Firefox only acts on a click if the anchor is actually in the document.
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }

  private handleBrowseError(error: HttpErrorResponse): void {
    if (error.status === 401) {
      this.passwordError = this.password.trim()
        ? 'That password is not correct.'
        : 'This link is password protected.';
      // Back to the gate rather than leaving a half-open folder on screen.
      this.opened = false;
      this.entries = [];
      return;
    }
    if (error.status === 404) {
      this.unavailable = true;
      this.share = null;
      return;
    }
    this.downloadError = 'The folder could not be opened. Please try again.';
  }

  private handleDownloadError(error: HttpErrorResponse): void {
    if (error.status === 401) {
      this.passwordError = this.password.trim()
        ? 'That password is not correct.'
        : 'This link is password protected.';
      return;
    }
    if (error.status === 404) {
      // The link expired or was revoked between opening the page and downloading.
      this.unavailable = true;
      this.share = null;
      return;
    }
    this.downloadError = 'The download failed. Please try again later.';
  }
}
