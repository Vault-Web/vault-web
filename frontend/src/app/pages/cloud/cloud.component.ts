import { CommonModule } from '@angular/common';
import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
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
import { TableLazyLoadEvent, TableModule } from 'primeng/table';
import { ToolbarModule } from 'primeng/toolbar';
import { FileDto } from '../../models/dtos/FileDto';
import { FolderDto } from '../../models/dtos/FolderDto';
import { FolderContentItemDto } from '../../models/dtos/FolderContentItemDto';
import { SearchResultDto } from '../../models/dtos/SearchResultDto';
import { CloudService } from '../../services/cloud.service';
import { finalize, firstValueFrom } from 'rxjs';
import { UiToastService } from '../../core/services/ui-toast.service';

interface Breadcrumb {
  name: string;
  path: string;
}

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
  ],
  providers: [ConfirmationService],
  templateUrl: './cloud.component.html',
  styleUrls: ['./cloud.component.scss'],
})
export class CloudComponent implements OnInit {
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
  editorMode: 'edit' | 'preview' | 'split' = 'edit';
  previewHtml: SafeHtml = '';

  showCreateFolderDialog = false;
  showRenameFolderDialog = false;
  showRenameFileDialog = false;
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
      sizeLabel: this.formatFileSize(item.size),
      typeLabel: item.directory ? 'Folder' : item.mimeType || 'Unknown',
      lastModifiedAt: item.lastModifiedAt,
    }));
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
    // Guard against out-of-order responses: when the user navigates or pages
    // quickly, an earlier (slower) request must not overwrite newer state.
    const requestId = ++this.contentRequestId;
    this.cloudService
      .getFolderContent(relativePath, page, this.pageSize, this.sort)
      .subscribe({
        next: (contentPage) => {
          if (requestId !== this.contentRequestId) return;
          this.entries = this.buildEntries(contentPage.content);
          this.totalElements = contentPage.totalElements;
          this.contentPage = contentPage.pageNumber;
          this.loading = false;
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
    const relativePath = this.getRelativePath(file.path);

    this.cloudService.getFileContent(relativePath).subscribe({
      next: (content) => {
        this.fileContent = content;
        this.editorMode = 'edit';
        this.updatePreview();
        this.showFileEditor = true;
      },
      error: (err) => {
        this.editingFile = null;
        this.toast.error('Could not open file', this.getErrorMessage(err));
      },
    });
  }

  async saveFile() {
    const nameToSave = this.newFileName.trim();
    if (!nameToSave) return;

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

  downloadFile(file: FileDto) {
    const pathKey = file.path;
    const relativePath = this.getRelativePath(file.path);
    this.downloadingPaths.add(pathKey);
    this.cloudService
      .getFileBlob(relativePath)
      .pipe(
        finalize(() => {
          this.downloadingPaths.delete(pathKey);
        }),
      )
      .subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = file.name;
          a.click();
          window.URL.revokeObjectURL(url);
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

  previewFileByPath(path: string, name: string) {
    this.previewFile(this.toFileRef(path, name));
  }

  closeFileEditor() {
    this.showFileEditor = false;
    this.editingFile = null;
    this.newFileName = '';
    this.fileContent = '';
    this.editorMode = 'edit';
    this.previewHtml = '';
  }

  isMarkdownFile(fileName: string): boolean {
    if (!fileName) return false;
    const ext = fileName.split('.').pop()?.toLowerCase();
    return ext === 'md' || ext === 'markdown';
  }

  updatePreview() {
    if (!this.isMarkdownFile(this.newFileName)) return;
    const rawMarkdown = this.fileContent || '';
    try {
      const dirtyHtml = marked.parse(rawMarkdown) as string;
      const cleanHtml = DOMPurify.sanitize(dirtyHtml);
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

  private getParentRelativePath(fullPath: string): string {
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
}
