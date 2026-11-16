import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { of, throwError } from 'rxjs';
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
    store = jasmine.createSpyObj('store', ['read', 'remove', 'cleanup']);
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
