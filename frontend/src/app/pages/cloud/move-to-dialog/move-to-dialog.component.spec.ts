import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { FolderDto } from '../../../models/dtos/FolderDto';
import { CloudService } from '../../../services/cloud.service';
import {
  MoveConfirmedEvent,
  MoveItem,
  MoveToDialogComponent,
} from './move-to-dialog.component';

describe('MoveToDialogComponent', () => {
  let fixture: ComponentFixture<MoveToDialogComponent>;
  let component: MoveToDialogComponent;
  let cloudMock: jasmine.SpyObj<CloudService>;

  const rootFolder: FolderDto = {
    name: 'root',
    path: 'root',
    folders: [
      {
        name: 'Docs',
        path: 'root/Docs',
        directChildrenCount: 1,
        lastModifiedAt: 0,
      },
      {
        name: 'Photos',
        path: 'root/Photos',
        directChildrenCount: 0,
        lastModifiedAt: 0,
      },
    ],
    files: [{ name: 'a.txt', path: 'root/a.txt', size: 1, mimeType: '' }],
    lastModifiedAt: 0,
  };

  const docsFolder: FolderDto = {
    name: 'Docs',
    path: 'root/Docs',
    folders: [
      {
        name: 'Work',
        path: 'root/Docs/Work',
        directChildrenCount: 0,
        lastModifiedAt: 0,
      },
    ],
    files: [],
    lastModifiedAt: 0,
  };

  /** Mirrors CloudComponent.getRelativePath for a root named "root". */
  const toRelative = (path: string): string =>
    path === 'root' ? '/' : path.replace(/^root\//, '');

  const fileItem: MoveItem = {
    path: 'root/a.txt',
    name: 'a.txt',
    kind: 'file',
  };

  beforeEach(async () => {
    cloudMock = jasmine.createSpyObj<CloudService>('CloudService', [
      'getRootFolder',
      'getFolderByPath',
    ]);
    cloudMock.getRootFolder.and.returnValue(of(rootFolder));
    cloudMock.getFolderByPath.and.returnValue(of(docsFolder));

    await TestBed.configureTestingModule({
      imports: [MoveToDialogComponent, NoopAnimationsModule],
      providers: [{ provide: CloudService, useValue: cloudMock }],
    }).compileComponents();

    fixture = TestBed.createComponent(MoveToDialogComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('toRelative', toRelative);
  });

  afterEach(() => fixture.destroy());

  function open(
    items: MoveItem[] = [fileItem],
    canMoveTo: (path: string) => boolean = () => true,
  ): void {
    fixture.componentRef.setInput('items', items);
    fixture.componentRef.setInput('canMoveTo', canMoveTo);
    fixture.componentRef.setInput('visible', true);
    fixture.detectChanges();
  }

  function folderButtons(): HTMLButtonElement[] {
    return Array.from(document.querySelectorAll('button.move-folder'));
  }

  function moveHereButton(): HTMLButtonElement | null {
    return document.querySelector('button[aria-label="Move here"]');
  }

  it('loads the root on open and lists its subfolders as buttons', () => {
    open();

    expect(cloudMock.getRootFolder).toHaveBeenCalledOnceWith(false);
    const buttons = folderButtons();
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('Docs');
    expect(buttons[1].textContent).toContain('Photos');
  });

  it('opens a folder, updates the breadcrumb, and goes back up', () => {
    open();

    folderButtons()[0].click();
    fixture.detectChanges();

    expect(cloudMock.getFolderByPath).toHaveBeenCalledOnceWith('Docs', false);
    expect(component.breadcrumbs.map((c) => c.name)).toEqual([
      'My files',
      'Docs',
    ]);
    expect(folderButtons()[0].textContent).toContain('Work');

    component.goUp();
    fixture.detectChanges();

    expect(cloudMock.getRootFolder).toHaveBeenCalledTimes(2);
    expect(component.breadcrumbs.map((c) => c.name)).toEqual(['My files']);
    expect(component.canGoUp).toBeFalse();
  });

  it('disables "Move here" when canMoveTo returns false', () => {
    open([fileItem], () => false);

    expect(component.canMoveHere).toBeFalse();
    expect(moveHereButton()?.disabled).toBeTrue();
    expect(component.moveHereReason).toBe('"a.txt" is already in this folder.');
  });

  it('enables "Move here" when canMoveTo returns true', () => {
    open([fileItem], () => true);

    expect(component.canMoveHere).toBeTrue();
    expect(moveHereButton()?.disabled).toBeFalse();
    expect(component.moveHereReason).toBe('');
  });

  it("can't enter a folder that is being moved, or one inside it", () => {
    const docs: MoveItem = { path: 'root/Docs', name: 'Docs', kind: 'folder' };
    open([docs], () => false);

    const docsButton = folderButtons()[0];
    expect(docsButton.disabled).toBeTrue();
    expect(docsButton.textContent).toContain("Can't move a folder into itself");
    expect(folderButtons()[1].disabled).toBeFalse();
    expect(
      component.isBlocked(docsFolder.folders[0]), // root/Docs/Work
    ).toBeTrue();
  });

  it('emits the browsed path and its existing names on "Move here"', () => {
    const emitted: MoveConfirmedEvent[] = [];
    component.moveConfirmed.subscribe((event) => emitted.push(event));
    open();

    moveHereButton()?.click();

    expect(emitted).toEqual([
      { targetPath: 'root', existingNames: ['Docs', 'Photos', 'a.txt'] },
    ]);
  });

  it('closes on "Cancel" without emitting a move', () => {
    const moves = jasmine.createSpy('moveConfirmed');
    const visibility = jasmine.createSpy('visibleChange');
    component.moveConfirmed.subscribe(moves);
    component.visibleChange.subscribe(visibility);
    open();

    document
      .querySelector<HTMLButtonElement>('button[aria-label="Cancel move"]')
      ?.click();

    expect(visibility).toHaveBeenCalledOnceWith(false);
    expect(moves).not.toHaveBeenCalled();
  });

  it("can't be closed while a move is running", () => {
    const visibility = jasmine.createSpy('visibleChange');
    component.visibleChange.subscribe(visibility);
    open();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();

    const cancel = document.querySelector<HTMLButtonElement>(
      'button[aria-label="Cancel move"]',
    );
    expect(cancel?.disabled).toBeTrue();
    component.cancel();
    component.onVisibleChange(false);

    expect(visibility).not.toHaveBeenCalled();
  });

  it('shows an error state on load failure and retries', () => {
    cloudMock.getRootFolder.and.returnValues(
      throwError(() => new Error('offline')),
      of(rootFolder),
    );
    open();

    expect(component.loadError).toBe('Could not load this folder.');
    expect(document.querySelector('.move-error')).not.toBeNull();
    expect(folderButtons().length).toBe(0);

    component.retry();
    fixture.detectChanges();

    expect(cloudMock.getRootFolder).toHaveBeenCalledTimes(2);
    expect(component.loadError).toBeNull();
    expect(folderButtons().length).toBe(2);
  });
});
