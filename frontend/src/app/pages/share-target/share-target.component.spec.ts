import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { of, throwError, Subject } from 'rxjs';
import { ShareTargetComponent, ShareDraft } from './share-target.component';
import { AuthService } from '../../services/auth.service';
import { CloudService } from '../../services/cloud.service';
import { FolderDto } from '../../models/dtos/FolderDto';

describe('Share target handoff', () => {
  const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  let auth: jasmine.SpyObj<AuthService>;
  let cloud: jasmine.SpyObj<CloudService>;
  let store: jasmine.SpyObj<Window['vaultShareStore']>;
  let draft: ShareDraft;
  let component: ShareTargetComponent;
  const folder: FolderDto = {
    name: 'root',
    path: '/owner',
    folders: [],
    files: [],
    lastModifiedAt: 0,
  };

  beforeEach(() => {
    auth = jasmine.createSpyObj('auth', ['isLoggedIn', 'getUsername']);
    auth.isLoggedIn.and.returnValue(true);
    auth.getUsername.and.returnValue('owner');
    cloud = jasmine.createSpyObj('cloud', ['getFolderByPath', 'uploadFile']);
    cloud.getFolderByPath.and.returnValue(of(folder));
    cloud.uploadFile.and.returnValue(of(folder));
    draft = {
      title: '<img src=x onerror=alert(1)>',
      text: 'private text',
      url: 'javascript:alert(1)',
      files: [new File(['one'], 'one.txt'), new File(['two'], 'two.txt')],
      expiresAt: Date.now() + 1800000,
    };
    store = jasmine.createSpyObj('store', [
      'read',
      'remove',
      'cleanup',
      'progress',
    ]);
    store.progress.and.resolveTo();
    store.read.and.callFake(async () => draft);
    store.remove.and.resolveTo();
    window.vaultShareStore = store;
    TestBed.configureTestingModule({
      imports: [ShareTargetComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: CloudService, useValue: cloud },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ id }) } },
        },
      ],
    });
    component = TestBed.createComponent(ShareTargetComponent).componentInstance;
  });
  afterEach(() => component.ngOnDestroy());

  it('retains unauthenticated content without calling Cloud', async () => {
    auth.isLoggedIn.and.returnValue(false);
    await component.ngOnInit();
    expect(component.draft).toBe(draft);
    expect(cloud.getFolderByPath).not.toHaveBeenCalled();
    await component.upload();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
    expect(store.remove).not.toHaveBeenCalled();
  });

  it('renders untrusted title and URL as text, without executable links', async () => {
    const fixture = TestBed.createComponent(ShareTargetComponent);
    component = fixture.componentInstance;
    await component.ngOnInit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(draft.title);
    expect(
      fixture.nativeElement.querySelector('a[href^="javascript:"]'),
    ).toBeNull();
  });

  it('requires confirmation and saves files and a literal text note with unique names', async () => {
    await component.ngOnInit();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
    await component.upload();
    const calls = cloud.uploadFile.calls.allArgs();
    expect(calls.length).toBe(3);
    expect(
      calls.every((args) => args[0] === '/owner' && args[3] === true),
    ).toBeTrue();
    expect(calls[0][1].name).toBe('shared-' + id + '-0-one.txt');
    expect(await calls[2][1].text()).toBe(
      draft.title + '\n\nprivate text\n\njavascript:alert(1)',
    );
    expect(component.complete).toBeTrue();
    expect(store.remove).toHaveBeenCalledOnceWith(id);
  });

  it('preserves ordinary punctuation and file bytes through confirmed uploads', async () => {
    draft.files = [
      new File(['invoice bytes'], 'john@example.com-invoice.pdf', {
        type: 'application/pdf',
      }),
      new File(['photo bytes'], 'Photo^1.jpg', { type: 'image/jpeg' }),
    ];
    await component.ngOnInit();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
    await component.upload();
    const files = cloud.uploadFile.calls.allArgs().map((args) => args[1]);
    expect(files[0].name).toBe(
      'shared-' + id + '-0-john@example.com-invoice.pdf',
    );
    expect(files[1].name).toBe('shared-' + id + '-1-Photo^1.jpg');
    expect(await files[0].text()).toBe('invoice bytes');
    expect(await files[1].text()).toBe('photo bytes');
    expect(files[0].type).toBe('application/pdf');
    expect(files[1].type).toBe('image/jpeg');
    expect(component.complete).toBeTrue();
  });

  it('retains failed content, locks destination, and retries only remaining files', async () => {
    cloud.uploadFile.and.returnValues(
      of(folder),
      throwError(() => ({ status: 413 })),
    );
    await component.ngOnInit();
    await component.upload();
    expect(component.uploaded.length).toBe(1);
    expect(component.draft).toBe(draft);
    expect(store.remove).not.toHaveBeenCalled();
    await component.openFolder('/elsewhere');
    expect(cloud.getFolderByPath.calls.count()).toBe(1);
    cloud.uploadFile.and.returnValue(of(folder));
    await component.upload();
    expect(cloud.uploadFile.calls.count()).toBe(4);
    expect(component.complete).toBeTrue();
  });

  it('does not upload to a destination selected under a different account', async () => {
    await component.ngOnInit();
    auth.getUsername.and.returnValue('other');
    await component.upload();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
    expect(component.error).toContain('account changed');
  });

  it('does not rebind ownership when the account changes before folder navigation', async () => {
    await component.ngOnInit();
    auth.getUsername.and.returnValue('other');
    await component.openFolder('/other');
    expect(cloud.getFolderByPath.calls.count()).toBe(1);
    await component.upload();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
  });

  it('rejects a folder response when the account changed while it was pending', async () => {
    const response = new Subject<FolderDto>();
    cloud.getFolderByPath.and.returnValue(response);
    const init = component.ngOnInit();
    await Promise.resolve();
    auth.getUsername.and.returnValue('other');
    response.next(folder);
    await init;
    expect(component.folder).toBeUndefined();
    await component.upload();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
  });

  it('recovers the locked destination and completed files from temporary storage', async () => {
    draft.destinationUser = 'owner';
    draft.destinationPath = '/owner/nested';
    draft.uploaded = ['shared-' + id + '-0-one.txt'];
    await component.ngOnInit();
    expect(cloud.getFolderByPath).toHaveBeenCalledWith(
      '/owner/nested',
      false,
      true,
    );
    await component.upload();
    expect(cloud.uploadFile.calls.count()).toBe(2);
    expect(store.progress).toHaveBeenCalled();
  });

  it('allows parent and sibling selection after the first upload fails and the draft reloads', async () => {
    const nested = { ...folder, path: '/owner/nested', name: 'nested' };
    const sibling = { ...folder, path: '/owner/sibling', name: 'sibling' };
    cloud.getFolderByPath.and.callFake((path) =>
      of(
        path === '/owner/nested'
          ? nested
          : path === '/owner/sibling'
            ? sibling
            : folder,
      ),
    );
    store.progress.and.callFake(async (_id, user, path, uploaded) => {
      draft.destinationUser = user;
      draft.destinationPath = path;
      draft.uploaded = [...uploaded];
    });
    cloud.uploadFile.and.returnValue(throwError(() => ({ status: 503 })));
    await component.ngOnInit();
    await component.openFolder(nested.path);
    await component.upload();
    expect(draft.destinationPath).toBe(nested.path);
    expect(draft.uploaded).toEqual([]);
    component.ngOnDestroy();
    const fixture = TestBed.createComponent(ShareTargetComponent);
    component = fixture.componentInstance;
    await component.ngOnInit();
    expect(component.rootPath).toBe('/owner');
    expect(component.folder?.path).toBe(nested.path);
    expect(component.folder?.path).not.toBe(component.rootPath);
    expect(component.parentPath()).toBe('/owner');
    expect(component.busy).toBeFalse();
    expect(component.uploaded).toEqual([]);
    await component.openFolder(component.parentPath());
    expect(component.folder?.path).toBe('/owner');
    await component.openFolder(sibling.path);
    cloud.uploadFile.and.returnValue(of(folder));
    await component.upload();
    expect(cloud.uploadFile.calls.mostRecent().args[0]).toBe(sibling.path);
    expect(component.complete).toBeTrue();
  });

  it('does not fall back to the root when a locked destination is unavailable', async () => {
    draft.destinationUser = 'owner';
    draft.destinationPath = '/owner/missing';
    draft.uploaded = ['shared-' + id + '-0-one.txt'];
    cloud.getFolderByPath.and.callFake((path) =>
      path === '/' ? of(folder) : throwError(() => ({ status: 404 })),
    );
    await component.ngOnInit();
    expect(component.rootPath).toBe('/owner');
    expect(component.folder).toBeUndefined();
    await component.upload();
    expect(cloud.uploadFile).not.toHaveBeenCalled();
    expect(component.draft).toBe(draft);
  });

  it('retries a failed locked destination recovery and uploads only remaining files', async () => {
    draft.destinationUser = 'owner';
    draft.destinationPath = '/owner/nested';
    draft.uploaded = ['shared-' + id + '-0-one.txt'];
    cloud.getFolderByPath.and.returnValue(throwError(() => ({ status: 503 })));
    await component.ngOnInit();
    expect(component.folder).toBeUndefined();
    const nested = { ...folder, path: '/owner/nested' };
    cloud.getFolderByPath.and.callFake((path) =>
      of(path === '/' ? folder : nested),
    );
    await component.loadDestination();
    expect(component.folder?.path).toBe(nested.path);
    await component.openFolder('/owner/sibling');
    expect(component.folder?.path).toBe(nested.path);
    await component.upload();
    expect(cloud.uploadFile.calls.count()).toBe(2);
    expect(
      cloud.uploadFile.calls.allArgs().every((args) => args[0] === nested.path),
    ).toBeTrue();
    expect(
      cloud.uploadFile.calls
        .allArgs()
        .some((args) => args[1].name.endsWith('-0-one.txt')),
    ).toBeFalse();
    expect(component.complete).toBeTrue();
  });

  it('handles missing drafts and unavailable destination without losing content', async () => {
    store.read.and.resolveTo(undefined);
    await component.ngOnInit();
    expect(component.error).toContain('expired');
    store.read.and.resolveTo(draft);
    cloud.getFolderByPath.and.returnValue(throwError(() => ({ status: 503 })));
    await component.ngOnInit();
    expect(component.folder).toBeUndefined();
    expect(component.draft).toBe(draft);
    expect(component.error).toContain('unavailable');
  });

  it('discards drafts without deleting completed Cloud uploads', async () => {
    await component.ngOnInit();
    component.uploaded = ['completed.txt'];
    await component.discard();
    expect(component.draft).toBeUndefined();
    expect(component.uploaded).toEqual(['completed.txt']);
    expect(store.remove).toHaveBeenCalledOnceWith(id);
  });
});
