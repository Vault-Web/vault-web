import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { CloudService } from '../../services/cloud.service';
import { FolderDto } from '../../models/dtos/FolderDto';

export interface ShareDraft {
  title: string;
  text: string;
  url: string;
  files: File[];
  expiresAt: number;
  destinationUser?: string;
  destinationPath?: string;
  uploaded?: string[];
}
declare global {
  interface Window {
    vaultShareStore: {
      read(id: string): Promise<ShareDraft | undefined>;
      remove(id: string): Promise<void>;
      cleanup(): Promise<void>;
      progress(
        id: string,
        destinationUser: string,
        destinationPath: string,
        uploaded: string[],
      ): Promise<void>;
    };
  }
}

@Component({
  selector: 'app-share-target',
  imports: [CommonModule, RouterLink],
  templateUrl: './share-target.component.html',
  styleUrl: './share-target.component.scss',
})
export class ShareTargetComponent implements OnInit, OnDestroy {
  id = '';
  draft?: ShareDraft;
  folder?: FolderDto;
  rootPath = '';
  error = '';
  loading = true;
  busy = false;
  complete = false;
  expired = false;
  uploaded: string[] = [];
  private uploadFiles: File[] = [];
  private destinationUser: string | null = null;
  private timer?: ReturnType<typeof setTimeout>;

  constructor(
    private route: ActivatedRoute,
    public auth: AuthService,
    private cloud: CloudService,
  ) {}

  async ngOnInit(): Promise<void> {
    this.id = this.route.snapshot.queryParamMap.get('id') || '';
    try {
      if (!/^[a-f0-9-]{36}$/.test(this.id)) {
        throw new Error(
          this.route.snapshot.queryParamMap.has('error')
            ? 'This share is unsupported, too large, or temporary storage is unavailable. Share up to 10 files / 50 MiB, or upload directly in Cloud.'
            : 'Share a file, link or text to the installed Vault Web app. Open Vault Web online once before using the mobile share target.',
        );
      }
      this.draft = await window.vaultShareStore.read(this.id);
      if (!this.draft)
        throw new Error(
          'This draft has expired or was replaced by a newer share. Please share it again.',
        );
      this.destinationUser = this.draft.destinationUser || null;
      this.uploaded = this.draft.uploaded || [];
      this.timer = setTimeout(
        () => {
          this.expired = true;
          this.draft = undefined;
          this.uploadFiles = [];
          void window.vaultShareStore.remove(this.id).catch(() => undefined);
        },
        Math.max(0, this.draft.expiresAt - Date.now()),
      );
      // Unique names avoid unintentionally replacing an existing Cloud file on retry.
      const prefix = 'shared-' + this.id + '-';
      this.uploadFiles = this.draft.files.map(
        (file, index) =>
          new File([file], prefix + index + '-' + file.name, {
            type: file.type,
          }),
      );
      if (this.draft.title || this.draft.text || this.draft.url) {
        this.uploadFiles.push(
          new File(
            [
              [this.draft.title, this.draft.text, this.draft.url]
                .filter(Boolean)
                .join('\n\n'),
            ],
            prefix + 'note.txt',
            { type: 'text/plain' },
          ),
        );
      }
      if (this.auth.isLoggedIn()) await this.loadDestination();
    } catch (error) {
      this.error =
        error instanceof Error
          ? error.message
          : 'Could not read the share draft. Please share it again.';
    } finally {
      this.loading = false;
    }
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }

  async loadDestination(): Promise<void> {
    if (this.busy || !this.draft || this.expired || this.complete) return;
    // Resolve the real Cloud root before restoring the persisted destination.
    // Recovery can retry a locked destination, never pick a different one.
    this.folder = undefined;
    await this.openFolder('/', true);
    const path = this.draft?.destinationPath;
    if (this.folder && path && path !== this.rootPath) {
      this.folder = undefined;
      await this.openFolder(path, true);
    }
  }

  async openFolder(path: string, recovering = false): Promise<void> {
    if (
      this.busy ||
      !this.auth.isLoggedIn() ||
      (this.uploaded.length && !recovering)
    )
      return;
    const user = this.auth.getUsername();
    if (!user || (this.destinationUser && user !== this.destinationUser)) {
      this.error =
        'The signed-in account changed. Share this content again for the new account.';
      return;
    }
    if (!this.destinationUser) this.destinationUser = user;
    this.busy = true;
    this.error = '';
    try {
      const folder = await firstValueFrom(
        this.cloud.getFolderByPath(path, false, true),
      );
      if (!this.auth.isLoggedIn() || this.auth.getUsername() !== user) {
        throw new Error('account changed');
      }
      if (!this.rootPath) this.rootPath = folder.path;
      this.folder = folder;
    } catch {
      this.error =
        'Cloud is unavailable or this folder cannot be accessed. Retry when online; your draft is retained for 30 minutes.';
    } finally {
      this.busy = false;
    }
  }

  parentPath(): string {
    return (
      this.folder?.path.substring(0, this.folder.path.lastIndexOf('/')) ||
      this.rootPath
    );
  }

  async upload(): Promise<void> {
    if (
      this.busy ||
      !this.folder ||
      !this.auth.isLoggedIn() ||
      this.expired ||
      this.complete
    )
      return;
    if (this.auth.getUsername() !== this.destinationUser) {
      this.error =
        'The signed-in account changed. Share this content again for the new account.';
      return;
    }
    this.busy = true;
    this.error = '';
    try {
      await window.vaultShareStore.progress(
        this.id,
        this.destinationUser!,
        this.folder.path,
        this.uploaded,
      );
      for (const file of this.uploadFiles) {
        if (this.uploaded.includes(file.name)) continue;
        if (
          this.expired ||
          !this.auth.isLoggedIn() ||
          this.auth.getUsername() !== this.destinationUser
        )
          throw new Error(
            'Session or draft expired, or account changed. Completed uploads are listed below; share remaining files again.',
          );
        await firstValueFrom(
          this.cloud.uploadFile(this.folder.path, file, undefined, true),
        );
        this.uploaded.push(file.name);
        await window.vaultShareStore.progress(
          this.id,
          this.destinationUser!,
          this.folder.path,
          this.uploaded,
        );
      }
      this.complete = true;
      this.uploadFiles = [];
      this.draft = undefined;
      clearTimeout(this.timer);
      await window.vaultShareStore.remove(this.id);
    } catch (error) {
      this.error =
        error instanceof Error
          ? error.message
          : 'Upload failed. Check your session, connection, storage quota and folder permissions, then retry. Completed files will not be uploaded again.';
    } finally {
      this.busy = false;
    }
  }

  async discard(): Promise<void> {
    if (this.busy) return;
    try {
      await window.vaultShareStore.remove(this.id);
      clearTimeout(this.timer);
      this.draft = undefined;
      this.uploadFiles = [];
      this.error = 'Share draft discarded. Completed Cloud uploads are kept.';
    } catch {
      this.error =
        'Could not discard the draft. Try again or clear this site’s storage.';
    }
  }
}
