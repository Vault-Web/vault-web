import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { FolderContentItemDto } from '../../models/dtos/FolderContentItemDto';
import { PublicShareDto } from '../../models/dtos/SecureSendLinkDto';
import { PublicShareService } from '../../services/public-share.service';
import { ShareComponent } from './share.component';

/**
 * The page an external recipient lands on. It has to work with no account and no
 * session: a file link downloads, a folder link browses, and the password — when
 * there is one — rides along on every request the recipient triggers.
 */
describe('ShareComponent', () => {
  let component: ShareComponent;
  let fixture: ComponentFixture<ShareComponent>;
  let shareServiceSpy: jasmine.SpyObj<PublicShareService>;

  const folderShare: PublicShareDto = {
    fileName: 'project',
    sizeBytes: 0,
    expiresAt: '2099-01-01T00:00:00Z',
    passwordProtected: false,
    resourceType: 'FOLDER',
  };

  const fileShare: PublicShareDto = {
    fileName: 'report.pdf',
    sizeBytes: 2048,
    expiresAt: '2099-01-01T00:00:00Z',
    passwordProtected: false,
    resourceType: 'FILE',
  };

  const entries: FolderContentItemDto[] = [
    {
      name: 'images',
      path: 'images',
      directory: true,
      size: 0,
      mimeType: null,
      lastModifiedAt: 1,
    },
    {
      name: 'readme.md',
      path: 'readme.md',
      directory: false,
      size: 12,
      mimeType: null,
      lastModifiedAt: 2,
    },
    {
      name: 'archive.zip',
      path: 'archive.zip',
      directory: false,
      size: 99,
      mimeType: 'application/zip',
      lastModifiedAt: 3,
    },
  ];

  function createComponent(): void {
    fixture = TestBed.createComponent(ShareComponent);
    component = fixture.componentInstance;
  }

  beforeEach(async () => {
    shareServiceSpy = jasmine.createSpyObj<PublicShareService>(
      'PublicShareService',
      [
        'describe',
        'content',
        'download',
        'view',
        'downloadFolder',
        'downloadUrl',
      ],
    );

    await TestBed.configureTestingModule({
      imports: [ShareComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [
        { provide: PublicShareService, useValue: shareServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: new Map([['token', 'tok']]) } },
        },
      ],
    }).compileComponents();
  });

  it('opens an unprotected folder link straight away', () => {
    shareServiceSpy.describe.and.returnValue(of(folderShare));
    shareServiceSpy.content.and.returnValue(of(entries));
    createComponent();

    component.ngOnInit();

    expect(component.isFolder).toBeTrue();
    expect(component.opened).toBeTrue();
    expect(component.entries.length).toBe(3);
    expect(shareServiceSpy.content).toHaveBeenCalledWith('tok', '', undefined);
  });

  it('holds a protected folder behind the password and unlocks it by listing', () => {
    shareServiceSpy.describe.and.returnValue(
      of({ ...folderShare, passwordProtected: true }),
    );
    createComponent();
    component.ngOnInit();

    // Nothing is listed before a password has been entered.
    expect(component.opened).toBeFalse();
    expect(shareServiceSpy.content).not.toHaveBeenCalled();

    component.unlockFolder();
    expect(component.passwordError).toBe(
      'Please enter the password for this link.',
    );

    shareServiceSpy.content.and.returnValue(
      throwError(() => ({ status: 401 })),
    );
    component.password = 'wrong';
    component.unlockFolder();
    expect(component.opened).toBeFalse();
    expect(component.passwordError).toBe('That password is not correct.');

    shareServiceSpy.content.and.returnValue(of(entries));
    component.password = 'right';
    component.unlockFolder();
    expect(component.opened).toBeTrue();
    expect(component.passwordError).toBe('');
    expect(shareServiceSpy.content).toHaveBeenCalledWith('tok', '', 'right');
  });

  it('navigates into sub-folders and builds a trail back out', () => {
    shareServiceSpy.describe.and.returnValue(of(folderShare));
    shareServiceSpy.content.and.returnValue(of(entries));
    createComponent();
    component.ngOnInit();

    component.onEntryOpen(entries[0]);

    expect(component.path).toBe('images');
    expect(shareServiceSpy.content).toHaveBeenCalledWith(
      'tok',
      'images',
      undefined,
    );
    expect(component.breadcrumbs.map((crumb) => crumb.label)).toEqual([
      'project',
      'images',
    ]);
  });

  it('previews what it can and downloads what it cannot', () => {
    shareServiceSpy.describe.and.returnValue(of(folderShare));
    shareServiceSpy.content.and.returnValue(of(entries));
    shareServiceSpy.view.and.returnValue(of(new Blob(['# hello'])));
    shareServiceSpy.downloadUrl.and.returnValue('/cloud-api/x');
    createComponent();
    component.ngOnInit();

    // A markdown file previews by the same rules the cloud view uses.
    component.onEntryOpen(entries[1]);
    expect(shareServiceSpy.view).toHaveBeenCalledWith(
      'tok',
      'readme.md',
      undefined,
    );
    expect(component.preview?.kind).toBe('text');

    // A zip can only be taken away, and an unprotected link lets the browser
    // fetch it rather than buffering it as a blob.
    component.closePreview();
    component.onEntryOpen(entries[2]);
    expect(component.preview).toBeNull();
    expect(shareServiceSpy.download).not.toHaveBeenCalled();
    expect(shareServiceSpy.downloadUrl).toHaveBeenCalledWith(
      'tok',
      'archive.zip',
      false,
    );
  });

  it('downloads the folder currently being viewed, not always the root', () => {
    shareServiceSpy.describe.and.returnValue(of(folderShare));
    shareServiceSpy.content.and.returnValue(of(entries));
    shareServiceSpy.downloadUrl.and.returnValue('/cloud-api/x');
    createComponent();
    component.ngOnInit();
    component.openFolder('images');

    component.downloadCurrentFolder();

    expect(shareServiceSpy.downloadUrl).toHaveBeenCalledWith(
      'tok',
      'images',
      true,
    );
    expect(component.archiving).toBeFalse();
  });

  it('leaves a single-file link on its download card', () => {
    shareServiceSpy.describe.and.returnValue(of(fileShare));
    shareServiceSpy.downloadUrl.and.returnValue('/cloud-api/x');
    createComponent();
    component.ngOnInit();

    expect(component.isFolder).toBeFalse();
    expect(shareServiceSpy.content).not.toHaveBeenCalled();

    component.download();

    expect(shareServiceSpy.downloadUrl).toHaveBeenCalledWith('tok', '', false);
  });

  it('keeps a protected link on the XHR path, which can carry the password', async () => {
    const { HttpResponse } = await import('@angular/common/http');
    shareServiceSpy.describe.and.returnValue(
      of({ ...folderShare, passwordProtected: true }),
    );
    shareServiceSpy.content.and.returnValue(of(entries));
    shareServiceSpy.downloadFolder.and.returnValue(
      of(new HttpResponse({ body: new Blob(['zip']) })),
    );
    createComponent();
    component.ngOnInit();
    component.password = 'right';
    component.unlockFolder();

    component.downloadCurrentFolder();

    // A plain navigation cannot send the password header, so this one may not
    // take the browser-streaming shortcut.
    expect(shareServiceSpy.downloadUrl).not.toHaveBeenCalled();
    expect(shareServiceSpy.downloadFolder).toHaveBeenCalledWith(
      'tok',
      '',
      'right',
    );
  });

  it('reports a link that disappears while it is open as unavailable', () => {
    shareServiceSpy.describe.and.returnValue(of(folderShare));
    shareServiceSpy.content.and.returnValue(of(entries));
    createComponent();
    component.ngOnInit();

    shareServiceSpy.content.and.returnValue(
      throwError(() => ({ status: 404 })),
    );
    component.openFolder('images');

    expect(component.unavailable).toBeTrue();
    expect(component.share).toBeNull();
  });
});
