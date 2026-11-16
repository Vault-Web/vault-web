import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, takeUntil } from 'rxjs';
import {
  Project,
  ProjectResource,
  ProjectRole,
  ProjectService,
} from '../../services/project.service';
import { CloudService } from '../../services/cloud.service';
import { ResourceShareService } from '../../services/resource-share.service';
import { GroupService } from '../../services/group.service';
import { GroupDto } from '../../models/dtos/GroupDto';
import { PrivateChatDialogComponent } from '../private-chat-dialog/private-chat-dialog.component';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [CommonModule, FormsModule, PrivateChatDialogComponent],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.scss',
})
export class ProjectsComponent implements OnInit, OnDestroy {
  readonly auth = inject(AuthService);
  private readonly service = inject(ProjectService);
  private readonly cloud = inject(CloudService);
  private readonly shares = inject(ResourceShareService);
  private readonly groups = inject(GroupService);
  private readonly destroy$ = new Subject<void>();
  private generation = 0;
  projects: Project[] = [];
  selected: Project | null = null;
  loading = false;
  busy = false;
  error = '';
  name = '';
  description = '';
  discussionId: number | null = null;
  memberName = '';
  memberRole: ProjectRole = 'MEMBER';
  path = '';
  folder = false;
  confirmingDelete = false;
  discussion: GroupDto | null = null;
  resourceMessage = '';

  ngOnInit() {
    this.load();
  }
  ngOnDestroy() {
    this.generation++;
    this.destroy$.next();
    this.destroy$.complete();
  }
  get manages() {
    return this.selected?.role === 'OWNER' || this.selected?.role === 'ADMIN';
  }
  get editable() {
    return this.manages && !this.selected?.archived;
  }

  load() {
    this.loading = true;
    this.error = '';
    this.service
      .list()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (list) => {
          this.projects = list;
          this.loading = false;
        },
        error: () => {
          this.loading = false;
          this.error = 'Projects could not be loaded. Please retry.';
        },
      });
  }
  select(id: number) {
    const generation = ++this.generation;
    this.selected = null;
    this.loading = true;
    this.error = '';
    this.confirmingDelete = false;
    this.discussion = null;
    this.resourceMessage = '';
    this.service
      .get(id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (project) => {
          if (generation !== this.generation) return;
          this.selected = project;
          this.name = project.name;
          this.description = project.description;
          this.discussionId = project.discussionGroupId;
          this.loading = false;
        },
        error: () => {
          if (generation !== this.generation) return;
          this.loading = false;
          this.error = 'Project is unavailable or you no longer have access.';
        },
      });
  }
  newProject() {
    this.generation++;
    this.loading = false;
    this.selected = null;
    this.name = '';
    this.description = '';
    this.discussionId = null;
    this.error = '';
    this.discussion = null;
    this.confirmingDelete = false;
  }
  save() {
    if (this.busy || !this.name.trim()) return;
    if (this.selected) {
      this.mutate(
        this.service.update(this.selected.id, {
          name: this.name,
          description: this.description,
          discussionGroupId: this.discussionId,
        }),
      );
    } else {
      this.busy = true;
      this.service
        .create(this.name, this.description, this.discussionId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: ({ id }) => {
            this.busy = false;
            this.load();
            this.select(id);
          },
          error: (error) => this.failure(error),
        });
    }
  }
  archive() {
    if (this.selected)
      this.mutate(
        this.service.update(this.selected.id, {
          archived: !this.selected.archived,
        }),
      );
  }
  addMember() {
    if (this.selected && this.memberName.trim())
      this.mutate(
        this.service.member(
          this.selected.id,
          this.memberName.trim(),
          this.memberRole,
        ),
      );
  }
  removeMember(userId: number) {
    if (this.selected)
      this.mutate(this.service.removeMember(this.selected.id, userId));
  }
  leave() {
    if (this.selected) this.mutate(this.service.leave(this.selected.id), true);
  }
  delete() {
    if (this.selected && this.confirmingDelete)
      this.mutate(this.service.delete(this.selected.id), true);
  }
  link() {
    if (this.selected && this.path.trim())
      this.mutate(
        this.service.link(this.selected.id, this.path.trim(), this.folder),
      );
  }
  unlink(id: number) {
    if (this.selected) this.mutate(this.service.unlink(this.selected.id, id));
  }
  openDiscussion() {
    const id = this.selected?.discussionGroupId;
    const generation = this.generation;
    if (id)
      this.groups
        .getGroupDetails(id)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (group) => {
            if (generation === this.generation) this.discussion = group;
          },
          error: () => {
            if (generation === this.generation)
              this.error =
                'Discussion is unavailable. Group membership is required.';
          },
        });
  }
  openResource(resource: ProjectResource) {
    if (!resource.available || !resource.path) return;
    const generation = this.generation;
    this.resourceMessage = 'Checking Cloud access…';
    const request = resource.folder
      ? resource.shareId
        ? this.shares.listContent(resource.shareId)
        : this.cloud.getFolderContent(resource.path)
      : resource.shareId
        ? this.shares.viewFile(resource.shareId)
        : this.cloud.getFileView(resource.path);
    (request as Observable<unknown>).pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        if (generation !== this.generation) return;
        if (result instanceof Blob) {
          const url = URL.createObjectURL(
            new Blob([result], { type: 'application/octet-stream' }),
          );
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = resource.path!.split('/').pop() || 'file';
          anchor.click();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
          this.resourceMessage =
            'File opened using your existing Cloud permissions.';
        } else {
          const entries = Array.isArray(result)
            ? result
            : (result as { content?: unknown[] }).content || [];
          this.resourceMessage = `Folder accessible: ${entries.length} entries on this page. Open Cloud to manage files.`;
        }
      },
      error: () => {
        if (generation === this.generation)
          this.resourceMessage =
            'Resource is unavailable, moved, or access was revoked. Refresh the project.';
      },
    });
  }
  private mutate(request: Observable<void>, reset = false) {
    if (this.busy || !this.selected) return;
    const id = this.selected.id;
    this.busy = true;
    this.error = '';
    request.pipe(takeUntil(this.destroy$)).subscribe({
      next: () => {
        this.busy = false;
        this.load();
        if (reset) this.newProject();
        else this.select(id);
      },
      error: (error) => this.failure(error),
    });
  }
  private failure(error: { status?: number; error?: { message?: string } }) {
    this.busy = false;
    this.error =
      error.status === 409
        ? error.error?.message || 'Project changed. Refresh before retrying.'
        : 'Could not save. Check permissions and inputs, then retry. Existing data is preserved.';
  }
}
