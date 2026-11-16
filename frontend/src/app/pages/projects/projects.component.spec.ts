import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { ProjectsComponent } from './projects.component';
import { Project, ProjectService } from '../../services/project.service';
import { CloudService } from '../../services/cloud.service';
import { ResourceShareService } from '../../services/resource-share.service';
import { GroupService } from '../../services/group.service';
import { AuthService } from '../../services/auth.service';

describe('ProjectsComponent', () => {
  let service: jasmine.SpyObj<ProjectService>;
  let component: ProjectsComponent;
  const project = (id = 1, role: Project['role'] = 'OWNER'): Project => ({
    id,
    name: 'Thesis',
    description: 'Private context',
    archived: false,
    createdAt: '2026-11-16',
    role,
    discussionGroupId: null,
    members: [{ userId: 1, username: 'owner', role: 'OWNER' }],
    resources: [
      {
        id: 3,
        available: false,
        path: null,
        ownerUsername: null,
        folder: null,
        shareId: null,
      },
    ],
    activity: [],
  });
  beforeEach(() => {
    service = jasmine.createSpyObj('ProjectService', [
      'list',
      'get',
      'create',
      'update',
      'member',
      'removeMember',
      'leave',
      'delete',
      'link',
      'unlink',
    ]);
    service.list.and.returnValue(of([project()]));
    service.get.and.returnValue(of(project()));
    TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: ProjectService, useValue: service },
        { provide: CloudService, useValue: {} },
        { provide: ResourceShareService, useValue: {} },
        { provide: GroupService, useValue: {} },
        { provide: AuthService, useValue: { getUsername: () => 'owner' } },
      ],
    });
    component = TestBed.createComponent(ProjectsComponent).componentInstance;
  });
  it('ignores a stale overview after selection changes', () => {
    const first = new Subject<Project>();
    const second = new Subject<Project>();
    service.get.and.returnValues(first, second);
    component.select(1);
    component.select(2);
    second.next(project(2));
    first.next(project(1));
    expect(component.selected?.id).toBe(2);
  });
  it('preserves form data and reports last-owner conflict', () => {
    component.select(1);
    component.name = 'Unsaved';
    service.leave.and.returnValue(
      throwError(() => ({
        status: 409,
        error: { message: 'A project must retain an owner' },
      })),
    );
    component.leave();
    expect(component.error).toContain('retain an owner');
    expect(component.name).toBe('Unsaved');
    expect(component.selected?.id).toBe(1);
  });
  it('requires explicit confirmation before deleting project metadata', () => {
    component.select(1);
    component.delete();
    expect(service.delete).not.toHaveBeenCalled();
    service.delete.and.returnValue(of(void 0));
    component.confirmingDelete = true;
    component.delete();
    expect(service.delete).toHaveBeenCalledWith(1);
    expect(component.selected).toBeNull();
  });
  it('renders unavailable resources without private paths and hides member management for members', () => {
    const fixture = TestBed.createComponent(ProjectsComponent);
    service.get.and.returnValue(of(project(1, 'MEMBER')));
    fixture.detectChanges();
    fixture.componentInstance.select(1);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Resource unavailable');
    expect(fixture.nativeElement.querySelector('#project-member')).toBeNull();
    expect(fixture.nativeElement.querySelector('#project-path')).toBeNull();
  });
  it('archive disables ordinary mutations but preserves leaving', () => {
    component.selected = { ...project(), archived: true };
    expect(component.editable).toBeFalse();
    expect(component.manages).toBeTrue();
  });

  it('preserves a hidden discussion link on rename unless removal is explicit', () => {
    component.select(1);
    service.update.and.returnValue(of(void 0));
    component.name = 'Renamed';
    component.save();
    expect(service.update).toHaveBeenCalledWith(
      1,
      jasmine.objectContaining({
        name: 'Renamed',
        discussionGroupId: undefined,
        clearDiscussion: false,
      }),
    );
    component.clearDiscussion = true;
    component.save();
    expect(service.update).toHaveBeenCalledWith(
      1,
      jasmine.objectContaining({
        clearDiscussion: true,
      }),
    );
  });
  it('resets pending detail and inputs when creating a new project', () => {
    const pending = new Subject<Project>();
    service.get.and.returnValue(pending);
    component.select(1);
    component.newProject();
    pending.next(project());
    expect(component.selected).toBeNull();
    expect(component.name).toBe('');
    expect(component.loading).toBeFalse();
  });
});
