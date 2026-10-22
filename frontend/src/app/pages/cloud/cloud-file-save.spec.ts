import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  provideHttpClientTesting,
  HttpTestingController,
  TestRequest,
} from '@angular/common/http/testing';
import { ConfirmationService } from 'primeng/api';
import { Router } from '@angular/router';
import { DomSanitizer } from '@angular/platform-browser';
import { CloudComponent } from './cloud.component';
import { CloudService } from '../../services/cloud.service';
import { ResourceShareService } from '../../services/resource-share.service';
import { UserService } from '../../services/user.service';
import { UiToastService } from '../../core/services/ui-toast.service';
import { environment } from '../../../environments/environment';

describe('Cloud editor save HTTP contract', () => {
  let component: CloudComponent;
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<UiToastService>;
  let reloadOwn: jasmine.Spy;
  let reloadShare: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    toast = jasmine.createSpyObj<UiToastService>('toast', ['success', 'error']);
    component = new CloudComponent(
      TestBed.inject(CloudService),
      {} as ConfirmationService,
      toast,
      {} as Router,
      {} as DomSanitizer,
      TestBed.inject(ResourceShareService),
      {} as UserService,
    );
    component.rootPath = '/root';
    component.currentFolder = { path: '/root/docs', name: 'docs' } as never;
    component.newFileName = 'notes.txt';
    component.fileContent = 'edited content';
    component.originalFileName = 'notes.txt';
    component.originalFileContent = 'original content';
    component.showFileEditor = true;
    reloadOwn = spyOn(component, 'navigateToFolder');
    reloadShare = spyOn(component, 'loadShareContent');
  });

  afterEach(() => http.verify());

  function existing(path = '/root/docs/notes.txt'): void {
    component.editingFile = {
      name: 'notes.txt',
      path,
      size: 16,
      mimeType: 'text/plain',
    };
  }

  function shared(resourceType: 'FILE' | 'FOLDER' = 'FOLDER'): void {
    component.shareCtx = {
      share: {
        id: 'share-1',
        ownerUsername: 'owner',
        recipientUsername: 'recipient',
        displayName: 'shared',
        relativePath: 'docs',
        resourceType,
        permissions: ['VIEW', 'EDIT'],
        createdAt: '2026-01-01T00:00:00Z',
        revoked: false,
      },
      sub: resourceType === 'FOLDER' ? 'nested' : '',
    };
  }

  function request(endpoint: string, method: string): TestRequest {
    const req = http.expectOne(
      (req) => req.url === environment.cloudServiceApiUrl + endpoint,
    );
    expect(req.request.method).toBe(method);
    return req;
  }

  async function uploaded(req: TestRequest, name = 'notes.txt'): Promise<void> {
    expect(req.request.body instanceof FormData).toBeTrue();
    const file = (req.request.body as FormData).get('file') as File;
    expect(file.name).toBe(name);
    expect(await file.text()).toBe('edited content');
  }

  function saved(title: string): void {
    expect(component.showFileEditor).toBeFalse();
    expect(component.editingFile).toBeNull();
    expect(component.isEditorDirty).toBeFalse();
    expect(toast.success).toHaveBeenCalledOnceWith(
      title,
      '"notes.txt" was saved.',
    );
    expect(toast.error).not.toHaveBeenCalled();
  }

  it('creates an own file without opting into replacement', async () => {
    component.newFileName = '  notes.txt  ';
    const save = component.saveFile();
    const req = request('/files/upload', 'POST');
    expect(req.request.params.get('folderPath')).toBe('docs');
    expect(req.request.headers.has('If-Match')).toBeFalse();
    await uploaded(req);
    req.flush({});
    await save;
    saved('File created');
    expect(reloadOwn).toHaveBeenCalledOnceWith('/root/docs');
    expect(reloadShare).not.toHaveBeenCalled();
  });

  it('updates an existing own file with an existence precondition', async () => {
    existing();
    const save = component.saveFile();
    const req = request('/files/upload', 'POST');
    expect(req.request.params.get('folderPath')).toBe('docs');
    expect(req.request.headers.get('If-Match')).toBe('*');
    await uploaded(req);
    req.flush({});
    await save;
    saved('File updated');
  });

  it('creates a file in a shared subfolder without a replacement header', async () => {
    shared();
    const save = component.saveFile();
    const req = request('/shares/share-1/upload', 'POST');
    expect(req.request.params.get('path')).toBe('nested');
    expect(req.request.headers.has('If-Match')).toBeFalse();
    await uploaded(req);
    req.flush(null);
    await save;
    saved('File created');
    expect(reloadShare).toHaveBeenCalledOnceWith('nested');
    expect(reloadOwn).not.toHaveBeenCalled();
  });

  it('edits the original shared path and filename through PUT', async () => {
    shared();
    existing('nested/notes.txt');
    component.newFileName = 'unexpected-rename.txt';
    const save = component.saveFile();
    const req = request('/shares/share-1/edit', 'PUT');
    expect(req.request.params.get('path')).toBe('nested/notes.txt');
    expect(req.request.headers.get('If-Match')).toBe('*');
    await uploaded(req);
    req.flush(null);
    await save;
    saved('File updated');
    expect(reloadShare).toHaveBeenCalledOnceWith('nested');
  });

  it('edits a single-file share using the empty share-relative path', async () => {
    shared('FILE');
    existing('');
    const save = component.saveFile();
    const req = request('/shares/share-1/edit', 'PUT');
    expect(req.request.params.get('path')).toBe('');
    expect(req.request.headers.get('If-Match')).toBe('*');
    req.flush(null);
    await save;
    saved('File updated');
  });

  for (const inShare of [false, true]) {
    for (const status of [409, 412]) {
      it(
        'keeps unsaved content on HTTP ' + status + ' (shared=' + inShare + ')',
        async () => {
          if (inShare) shared();
          if (status === 412)
            existing(inShare ? 'nested/notes.txt' : '/root/docs/notes.txt');
          const editingFile = component.editingFile;
          const save = component.saveFile();
          const endpoint = inShare
            ? status === 412
              ? '/shares/share-1/edit'
              : '/shares/share-1/upload'
            : '/files/upload';
          const req = request(
            endpoint,
            inShare && status === 412 ? 'PUT' : 'POST',
          );
          req.flush('The file conflicts with the current state.', {
            status,
            statusText: status === 409 ? 'Conflict' : 'Precondition Failed',
          });
          await save;
          expect(component.showFileEditor).toBeTrue();
          expect(component.fileContent).toBe('edited content');
          expect(component.originalFileContent).toBe('original content');
          expect(component.newFileName).toBe('notes.txt');
          expect(component.editingFile).toBe(editingFile);
          expect(toast.error).toHaveBeenCalledOnceWith(
            'Save failed',
            'The file conflicts with the current state.',
          );
          expect(toast.success).not.toHaveBeenCalled();
          expect(reloadOwn).not.toHaveBeenCalled();
          expect(reloadShare).not.toHaveBeenCalled();
        },
      );
    }
  }

  it('shows structured error messages as well as string responses', async () => {
    const save = component.saveFile();
    const req = request('/files/upload', 'POST');
    req.flush(
      { message: 'Storage quota exceeded' },
      { status: 400, statusText: 'Bad Request' },
    );
    await save;
    expect(toast.error).toHaveBeenCalledOnceWith(
      'Save failed',
      'Storage quota exceeded',
    );
    expect(component.showFileEditor).toBeTrue();
  });

  it('does not send a save request for a blank filename', async () => {
    component.newFileName = '  ';
    await component.saveFile();
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.error).not.toHaveBeenCalled();
    expect(component.showFileEditor).toBeTrue();
  });

  it('renames an own file before saving its contents conditionally', async () => {
    existing();
    component.newFileName = 'renamed.txt';
    const save = component.saveFile();
    const rename = request('/files/move', 'PATCH');
    expect(rename.request.params.get('filePath')).toBe('docs/notes.txt');
    expect(rename.request.params.get('newPath')).toBe('docs/renamed.txt');
    http.expectNone(
      (req) => req.url === environment.cloudServiceApiUrl + '/files/upload',
    );
    rename.flush({});
    await Promise.resolve();
    const upload = request('/files/upload', 'POST');
    expect(upload.request.headers.get('If-Match')).toBe('*');
    await uploaded(upload, 'renamed.txt');
    upload.flush({});
    await save;
    expect(toast.success).toHaveBeenCalledOnceWith(
      'File updated',
      '"renamed.txt" was saved.',
    );
  });

  it('does not upload or close the editor if renaming fails', async () => {
    existing();
    component.newFileName = 'taken.txt';
    const save = component.saveFile();
    const rename = request('/files/move', 'PATCH');
    rename.flush('Destination already exists', {
      status: 409,
      statusText: 'Conflict',
    });
    await save;
    http.expectNone(
      (req) => req.url === environment.cloudServiceApiUrl + '/files/upload',
    );
    expect(component.showFileEditor).toBeTrue();
    expect(component.fileContent).toBe('edited content');
    expect(toast.error).toHaveBeenCalledOnceWith(
      'Save failed',
      'Destination already exists',
    );
  });
});

describe('Cloud save request header serialization', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  for (const ifMatch of [undefined, '*', '"version-1"']) {
    it('preserves the own-file precondition ' + String(ifMatch), () => {
      TestBed.inject(CloudService)
        .uploadFile('/', new File(['content'], 'a.txt'), ifMatch)
        .subscribe();
      const req = http.expectOne(
        (req) => req.url === environment.cloudServiceApiUrl + '/files/upload',
      );
      expect(req.request.params.get('folderPath')).toBe('/');
      expect(req.request.headers.get('If-Match')).toBe(ifMatch ?? null);
      req.flush({});
    });

    it(
      'preserves the share precondition and encodes the share id ' +
        String(ifMatch),
      () => {
        TestBed.inject(ResourceShareService)
          .editFile(
            'share/id',
            'nested/a.txt',
            new File(['content'], 'a.txt'),
            ifMatch,
          )
          .subscribe();
        const req = http.expectOne(
          (req) =>
            req.url ===
            environment.cloudServiceApiUrl + '/shares/share%2Fid/edit',
        );
        expect(req.request.method).toBe('PUT');
        expect(req.request.params.get('path')).toBe('nested/a.txt');
        expect(req.request.headers.get('If-Match')).toBe(ifMatch ?? null);
        req.flush(null);
      },
    );
  }
});
