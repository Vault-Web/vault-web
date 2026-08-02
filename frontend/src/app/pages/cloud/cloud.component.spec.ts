import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError, Subject } from 'rxjs';
import { ConfirmationService } from 'primeng/api';
import { UiToastService } from '../../core/services/ui-toast.service';
import { CloudComponent } from './cloud.component';
import { CloudService } from '../../services/cloud.service';
import { ScanJobDto } from '../../models/dtos/ScanJobDto';
import { SecureSendLinkDto } from '../../models/dtos/SecureSendLinkDto';

/**
 * Exercises the folder virus-scan state machine end to end with a mocked
 * CloudService and Jasmine's fake clock driving the poll timer.
 */
describe('CloudComponent virus scan', () => {
  let component: CloudComponent;
  let cloudMock: jasmine.SpyObj<CloudService>;

  const runningJob: ScanJobDto = {
    jobId: 'job-1',
    path: '',
    status: 'RUNNING',
    filesScanned: 2,
    infectedCount: 0,
  };

  const noopToast = {
    success: () => {},
    info: () => {},
    warn: () => {},
    error: () => {},
  };

  beforeEach(() => {
    jasmine.clock().install();
    cloudMock = jasmine.createSpyObj<CloudService>('CloudService', [
      'startFolderScan',
      'getScanJob',
    ]);
    component = new CloudComponent(
      cloudMock,
      {} as never,
      noopToast as never,
      {} as never,
      {} as never,
      {} as never,
      {} as never,
    );
    component.rootPath = '/root';
    component.currentFolder = { path: '/root', name: 'root' } as never;
  });

  afterEach(() => jasmine.clock().uninstall());

  it('polls until COMPLETED, then stops and exposes infected findings', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    const stillRunning: ScanJobDto = { ...runningJob, filesScanned: 3 };
    const completed: ScanJobDto = {
      jobId: 'job-1',
      path: '',
      status: 'COMPLETED',
      filesScanned: 3,
      infectedCount: 1,
      findings: [{ path: 'a/evil.exe', verdict: 'INFECTED', detail: 'Eicar' }],
    };
    cloudMock.getScanJob.and.returnValues(of(stillRunning), of(completed));

    component.scanCurrentFolder();
    expect(component.scanning).toBeTrue();
    expect(component.scanJob?.status).toBe('RUNNING');

    jasmine.clock().tick(1200); // first poll -> still running -> reschedule
    expect(component.scanning).toBeTrue();

    jasmine.clock().tick(1200); // second poll -> completed
    expect(component.scanning).toBeFalse();
    expect(component.scanJob?.status).toBe('COMPLETED');
    expect(component.scanInfectedFindings.length).toBe(1);
    expect(component.scanNoThreats).toBeFalse();

    const callsSoFar = cloudMock.getScanJob.calls.count();
    jasmine.clock().tick(6000); // terminal: must not poll again
    expect(cloudMock.getScanJob.calls.count()).toBe(callsSoFar);
  });

  it('detects a disabled scanner (every file errored) with a clear message', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(
      of({
        jobId: 'job-1',
        path: '',
        status: 'COMPLETED',
        filesScanned: 2,
        infectedCount: 0,
        findings: [
          {
            path: 'a.txt',
            verdict: 'ERROR',
            detail: 'virus scanning is disabled',
          },
          {
            path: 'b.txt',
            verdict: 'ERROR',
            detail: 'virus scanning is disabled',
          },
        ],
      }),
    );

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);

    expect(component.scanScannerUnavailable).toBeTrue();
    expect(component.scanUnavailableMessage).toContain('turned off');
    expect(component.scanNoThreats).toBeFalse();
  });

  it('treats an unreachable scanner as unavailable (no "disabled" detail)', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(
      of({
        jobId: 'job-1',
        path: '',
        status: 'COMPLETED',
        filesScanned: 1,
        infectedCount: 0,
        findings: [
          { path: 'a.txt', verdict: 'ERROR', detail: 'connection refused' },
        ],
      }),
    );

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);

    expect(component.scanScannerUnavailable).toBeTrue();
    expect(component.scanUnavailableMessage).toContain('unavailable');
  });

  it('reports a clean scan as no threats', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(
      of({
        jobId: 'job-1',
        path: '',
        status: 'COMPLETED',
        filesScanned: 4,
        infectedCount: 0,
        findings: [],
      }),
    );

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);

    expect(component.scanNoThreats).toBeTrue();
    expect(component.scanScannerUnavailable).toBeFalse();
    expect(component.scanInfectedFindings.length).toBe(0);
  });

  it('surfaces a rate-limit (429) on start and never polls', () => {
    cloudMock.startFolderScan.and.returnValue(
      throwError(() => ({ status: 429 })),
    );

    component.scanCurrentFolder();

    expect(component.scanning).toBeFalse();
    expect(component.scanError).toContain('wait');
    jasmine.clock().tick(6000);
    expect(cloudMock.getScanJob).not.toHaveBeenCalled();
  });

  it('keeps the scan alive when polling is rate limited, and still completes', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    // throttled once, then the backend lets us through again
    cloudMock.getScanJob.and.returnValues(
      throwError(() => ({ status: 429 })),
      of({ ...runningJob, status: 'COMPLETED', findings: [] }),
    );

    component.scanCurrentFolder();
    jasmine.clock().tick(1200); // first poll -> 429

    expect(component.scanning).toBeTrue();
    expect(component.scanError).toBeUndefined();

    jasmine.clock().tick(5000); // backoff elapses -> poll succeeds
    expect(component.scanning).toBeFalse();
    expect(component.scanError).toBeUndefined();
    expect(component.scanJob?.status).toBe('COMPLETED');
  });

  it('gives up on a persistently rate-limited scan without claiming it failed', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(throwError(() => ({ status: 429 })));

    component.scanCurrentFolder();
    jasmine.clock().tick(1200); // first poll
    jasmine.clock().tick(5000 * 5); // exhaust the retries

    expect(component.scanning).toBeFalse();
    expect(component.scanError).toContain('may still be running');
    expect(cloudMock.getScanJob).toHaveBeenCalledTimes(6); // initial + 5 retries
  });

  it('handles an expired job (404) during polling', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(throwError(() => ({ status: 404 })));

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);

    expect(component.scanning).toBeFalse();
    expect(component.scanError).toContain('no longer available');
  });

  it('stops polling once the dialog is closed mid-scan', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(of({ ...runningJob })); // never terminal

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);
    const calls = cloudMock.getScanJob.calls.count();

    component.onScanDialogHide();
    jasmine.clock().tick(6000);

    expect(cloudMock.getScanJob.calls.count()).toBe(calls);
  });

  it('does not start polling if closed while the start request is in flight', () => {
    const start$ = new Subject<ScanJobDto>();
    cloudMock.startFolderScan.and.returnValue(start$.asObservable());
    cloudMock.getScanJob.and.returnValue(of({ ...runningJob }));

    component.scanCurrentFolder(); // POST in flight
    component.onScanDialogHide(); // user closes before it resolves
    start$.next({ ...runningJob }); // POST resolves late
    start$.complete();
    jasmine.clock().tick(6000);
  });
});

describe('CloudComponent Unsaved Changes Flow', () => {
  let component: CloudComponent;
  let cloudMock: jasmine.SpyObj<CloudService>;
  let confirmMock: jasmine.SpyObj<ConfirmationService>;
  let toastMock: jasmine.SpyObj<UiToastService>;

  beforeEach(() => {
    cloudMock = jasmine.createSpyObj<CloudService>('CloudService', [
      'getFileContent',
      'uploadFile',
      'renameOrMoveFile',
      'getFolderByPath',
      'getFolderContent',
    ]);
    cloudMock.getFolderByPath.and.returnValue(
      of({ path: '/root', name: 'root', entries: [] } as any),
    );
    cloudMock.getFolderContent.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, pageNumber: 0 }),
    );

    confirmMock = jasmine.createSpyObj<ConfirmationService>(
      'ConfirmationService',
      ['confirm'],
    );
    toastMock = jasmine.createSpyObj<UiToastService>('UiToastService', [
      'success',
      'error',
    ]);

    const sanitizerMock = {
      bypassSecurityTrustHtml: (html: string) => html,
    };

    component = new CloudComponent(
      cloudMock,
      confirmMock,
      toastMock as any,
      {} as any,
      sanitizerMock as any,
      {} as any,
      {} as any,
    );
  });

  it('should track original content and not be dirty initially', () => {
    expect(component.isEditorDirty).toBeFalse();
  });

  it('should not be dirty when file is opened and unchanged', () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';
    expect(component.isEditorDirty).toBeFalse();
  });

  it('should be dirty when file content is changed', () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello world';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';
    expect(component.isEditorDirty).toBeTrue();
  });

  it('should be dirty when file name is changed', () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello';
    component.originalFileName = 'a.txt';
    component.newFileName = 'b.txt';
    expect(component.isEditorDirty).toBeTrue();
  });

  it('should prompt user on requestCloseFileEditor when dirty', () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello world';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';

    component.requestCloseFileEditor();

    expect(confirmMock.confirm).toHaveBeenCalled();
  });

  it('should not prompt user on requestCloseFileEditor when not dirty', () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';

    component.requestCloseFileEditor();

    expect(confirmMock.confirm).not.toHaveBeenCalled();
    expect(component.showFileEditor).toBeFalse();
  });

  it('should reset state and close editor upon successful save', async () => {
    component.showFileEditor = true;
    component.editingFile = {
      path: '/a.txt',
      name: 'a.txt',
      size: 0,
      mimeType: 'text/plain',
    };
    component.newFileName = 'a.txt';
    component.fileContent = 'hello world';
    component.originalFileContent = 'hello';
    component.originalFileName = 'a.txt';
    component.currentFolder = { path: '/root', name: 'root' } as any;

    cloudMock.uploadFile.and.returnValue(of({} as any));

    await component.saveFile();

    expect(component.showFileEditor).toBeFalse();
    expect(component.isEditorDirty).toBeFalse();
  });

  it('should return false in canDeactivate and prompt user when dirty', async () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello world';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';

    confirmMock.confirm.and.callFake((config) => {
      if (config.reject) config.reject();
      return confirmMock;
    });

    const result = await component.canDeactivate();
    expect(result).toBeFalse();
    expect(confirmMock.confirm).toHaveBeenCalled();
  });

  it('should return true in canDeactivate and close editor when accepted', async () => {
    component.showFileEditor = true;
    component.originalFileContent = 'hello';
    component.fileContent = 'hello world';
    component.originalFileName = 'a.txt';
    component.newFileName = 'a.txt';

    confirmMock.confirm.and.callFake((config) => {
      if (config.accept) config.accept();
      return confirmMock;
    });

    const result = await component.canDeactivate();
    expect(result).toBeTrue();
    expect(confirmMock.confirm).toHaveBeenCalled();
    expect(component.showFileEditor).toBeFalse();
  });

  describe('Markdown Workspace Mode', () => {
    beforeEach(() => {
      jasmine.clock().install();
    });

    afterEach(() => {
      jasmine.clock().uninstall();
    });

    it('should generate document outline from content headings', () => {
      component.fileContent =
        '# Heading 1\nSome text\n## Heading 2\n### Heading 3';
      component.updateOutline();
      expect(component.outline.length).toBe(3);
      expect(component.outline[0]).toEqual({ text: 'Heading 1', level: 1 });
      expect(component.outline[1]).toEqual({ text: 'Heading 2', level: 2 });
      expect(component.outline[2]).toEqual({ text: 'Heading 3', level: 3 });
    });

    it('should parse wikilinks to anchor tags during preview update', () => {
      component.newFileName = 'doc.md';
      component.fileContent =
        'Check out [[another-doc]] and [[my folder/notes]].';
      component.updatePreview();

      const parsedHtml = component.previewHtml.toString();
      expect(parsedHtml).toContain('data-target="another-doc"');
      expect(parsedHtml).toContain('data-target="my folder/notes"');
    });

    it('should resolve wikilinks correctly if matching file exists', () => {
      component.entries = [
        {
          kind: 'file',
          name: 'target-note.md',
          path: '/root/target-note.md',
          size: 0,
          mimeType: 'text/markdown',
        } as any,
      ];

      const fileRef = component.resolveWikilink('target-note');
      expect(fileRef).not.toBeNull();
      expect(fileRef?.name).toBe('target-note.md');
      expect(fileRef?.path).toBe('/root/target-note.md');

      const missingFileRef = component.resolveWikilink('non-existent');
      expect(missingFileRef).toBeNull();
    });

    it('should handle click on wikilink and open matched file', () => {
      spyOn(component, 'editFile');
      component.entries = [
        {
          kind: 'file',
          name: 'target-note.md',
          path: '/root/target-note.md',
          size: 0,
          mimeType: 'text/markdown',
        } as any,
      ];

      const mockEvent = {
        target: {
          classList: {
            contains: (cls: string) => cls === 'wikilink',
          },
          getAttribute: (attr: string) =>
            attr === 'data-target' ? 'target-note' : null,
        },
        preventDefault: jasmine.createSpy('preventDefault'),
      } as any;

      component.handlePreviewClick(mockEvent);
      expect(mockEvent.preventDefault).toHaveBeenCalled();
      expect(component.editFile).toHaveBeenCalledWith({
        path: '/root/target-note.md',
        name: 'target-note.md',
        size: 0,
        mimeType: '',
      });
    });

    it('should transition saveStatus through unsaved -> saving -> saved during autosave', async () => {
      component.showFileEditor = true;
      component.newFileName = 'note.md';
      component.fileContent = 'new markdown';
      component.originalFileContent = '';
      component.originalFileName = 'note.md';
      component.currentFolder = { path: '/root', name: 'root' } as any;

      cloudMock.uploadFile.and.returnValue(of({} as any));

      component.onContentChange();
      expect(component.saveStatus).toBe('unsaved');

      jasmine.clock().tick(2000);

      // Wait for async autosave Promise to resolve
      await component.autosaveFile();

      expect(component.saveStatus).toBe('saved');
      expect(component.originalFileContent).toBe('new markdown');
      expect(component.isEditorDirty).toBeFalse();
    });
  });
});

describe('CloudComponent File Checksum', () => {
  let component: CloudComponent;
  let cloudMock: jasmine.SpyObj<CloudService>;
  let toastMock: { success: jasmine.Spy; error: jasmine.Spy };

  beforeEach(() => {
    cloudMock = jasmine.createSpyObj<CloudService>('CloudService', [
      'getFileChecksum',
    ]);
    toastMock = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };
    component = new CloudComponent(
      cloudMock,
      {} as never,
      toastMock as never,
      {} as never,
      {} as never,
      {} as never,
      {} as never,
    );
    component.rootPath = '/root';
  });

  it('should open dialog and load file checksum successfully', () => {
    const mockChecksum = {
      filePath: 'test.pdf',
      checksum:
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      algorithm: 'SHA-256',
    };
    cloudMock.getFileChecksum.and.returnValue(of(mockChecksum));

    component.openChecksumDialog({
      name: 'test.pdf',
      path: '/root/test.pdf',
    });

    expect(component.showChecksumDialog).toBeTrue();
    expect(component.selectedFileForChecksum?.name).toBe('test.pdf');
    expect(component.checksumLoading).toBeFalse();
    expect(component.checksumResult?.checksum).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
  });

  it('should handle checksum error and invoke toast notification', () => {
    cloudMock.getFileChecksum.and.returnValue(
      throwError(() => new Error('Checksum endpoint failure')),
    );

    component.openChecksumDialog({
      name: 'corrupted.zip',
      path: '/root/corrupted.zip',
    });

    expect(component.checksumLoading).toBeFalse();
    expect(component.checksumError).toContain('Checksum endpoint failure');
    expect(toastMock.error).toHaveBeenCalled();
  });

  it('should evaluate expected hash comparison correctly', () => {
    component.checksumResult = {
      filePath: 'doc.txt',
      checksum: 'A1B2C3D4E5',
      algorithm: 'SHA-256',
    };

    component.expectedHash = '';
    expect(component.hashMatchStatus).toBe('empty');

    component.expectedHash = 'a1b2c3d4e5';
    expect(component.hashMatchStatus).toBe('match');

    component.expectedHash = 'wronghash123';
    expect(component.hashMatchStatus).toBe('mismatch');
  });

  describe('Clipboard Copy functionality', () => {
    let originalClipboard: any;

    beforeEach(() => {
      originalClipboard = navigator.clipboard;
    });

    afterEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: originalClipboard,
        configurable: true,
        writable: true,
      });
    });

    it('should copy checksum to clipboard and set success toast notification on success', fakeAsync(() => {
      component.checksumResult = {
        filePath: 'doc.txt',
        checksum: '1234567890abcdef',
        algorithm: 'SHA-256',
      };

      const writeTextSpy = jasmine
        .createSpy('writeText')
        .and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextSpy },
        configurable: true,
        writable: true,
      });

      component.copyChecksumToClipboard();
      tick();

      expect(writeTextSpy).toHaveBeenCalledWith('1234567890abcdef');
      expect(component.copiedHashState).toBeTrue();
      expect(toastMock.success).toHaveBeenCalledWith(
        'Checksum Copied',
        jasmine.any(String),
      );
    }));

    it('should handle clipboard writeText failure and show error toast', fakeAsync(() => {
      component.checksumResult = {
        filePath: 'doc.txt',
        checksum: '1234567890abcdef',
        algorithm: 'SHA-256',
      };

      const writeTextSpy = jasmine
        .createSpy('writeText')
        .and.returnValue(Promise.reject('error'));
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextSpy },
        configurable: true,
        writable: true,
      });

      component.copyChecksumToClipboard();
      tick();

      expect(writeTextSpy).toHaveBeenCalledWith('1234567890abcdef');
      expect(component.copiedHashState).toBeFalse();
      expect(toastMock.error).toHaveBeenCalledWith(
        'Copy Failed',
        jasmine.any(String),
      );
    }));

    it('should handle unsupported clipboard API and show error toast', () => {
      component.checksumResult = {
        filePath: 'doc.txt',
        checksum: '1234567890abcdef',
        algorithm: 'SHA-256',
      };

      Object.defineProperty(navigator, 'clipboard', {
        value: undefined,
        configurable: true,
        writable: true,
      });

      component.copyChecksumToClipboard();

      expect(component.copiedHashState).toBeFalse();
      expect(toastMock.error).toHaveBeenCalledWith(
        'Copy Failed',
        jasmine.any(String),
      );
    });
  });
});

describe('CloudComponent Secure Send Flow', () => {
  let component: CloudComponent;
  let fixture: ComponentFixture<CloudComponent>;
  let cloudServiceSpy: jasmine.SpyObj<CloudService>;
  let toastSpy: jasmine.SpyObj<UiToastService>;

  beforeEach(async () => {
    cloudServiceSpy = jasmine.createSpyObj('CloudService', [
      'getRootFolder',
      'getFolderByPath',
      'getFolderContent',
      'createSecureSendLink',
      'listSecureSendLinks',
      'revokeSecureSendLink',
    ]);

    cloudServiceSpy.getRootFolder.and.returnValue(
      of({ id: 'root', name: 'Root', path: '/' } as any),
    );
    cloudServiceSpy.getFolderContent.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, page: 0 } as any),
    );

    toastSpy = jasmine.createSpyObj('UiToastService', [
      'success',
      'error',
      'info',
      'warn',
    ]);

    await TestBed.configureTestingModule({
      imports: [
        CloudComponent,
        HttpClientTestingModule,
        RouterTestingModule,
        NoopAnimationsModule,
      ],
      providers: [
        { provide: CloudService, useValue: cloudServiceSpy },
        { provide: UiToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CloudComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should open create share link dialog for a file', () => {
    component.openCreateShareDialog('/file.txt', 'file.txt');
    expect(component.selectedFileForShare).toEqual({
      path: '/file.txt',
      name: 'file.txt',
    });
    expect(component.showCreateShareDialog).toBeTrue();
    expect(component.shareExpiryMinutes).toBe(1440);
  });

  it('should create a secure send link and open generated URL dialog on success', () => {
    component.selectedFileForShare = { path: '/doc.pdf', name: 'doc.pdf' };
    component.shareExpiryMinutes = 60;
    component.sharePassword = 'pass';

    const mockLink: SecureSendLinkDto = {
      id: 's1',
      filePath: '/doc.pdf',
      fileName: 'doc.pdf',
      shareUrl: 'http://localhost/share/s1',
      expiresAt: '2026-07-21T00:00:00Z',
      createdAt: '2026-07-20T00:00:00Z',
    };

    cloudServiceSpy.createSecureSendLink.and.returnValue(of(mockLink));

    component.submitCreateShareLink();

    expect(cloudServiceSpy.createSecureSendLink).toHaveBeenCalledWith(
      '/doc.pdf',
      60,
      'pass',
    );
    expect(component.createdShareUrl).toBe('http://localhost/share/s1');
    expect(component.showCreateShareDialog).toBeFalse();
    expect(component.showGeneratedLinkDialog).toBeTrue();
    expect(toastSpy.success).toHaveBeenCalledWith(
      'Share link created',
      'Link for "doc.pdf" created successfully.',
    );
  });

  it('should handle rate limit error (HTTP 429) when creating share link', () => {
    component.selectedFileForShare = { path: '/doc.pdf', name: 'doc.pdf' };
    cloudServiceSpy.createSecureSendLink.and.returnValue(
      throwError(() => ({ status: 429, message: 'Too Many Requests' })),
    );

    component.submitCreateShareLink();

    expect(toastSpy.error).toHaveBeenCalledWith(
      'Rate limit reached',
      'Too many share links created. Please wait before trying again.',
    );
  });
});

/**
 * The unsaved-changes guard on the Cloud file editor: every exit path (Cancel,
 * the dialog dismiss, and a full-page leave) must confirm before discarding
 * edits, and a clean editor must close without a prompt.
 */
describe('CloudComponent unsaved-edit guard', () => {
  let component: CloudComponent;
  let confirmMock: jasmine.SpyObj<{ confirm: (config: unknown) => void }>;

  beforeEach(() => {
    confirmMock = jasmine.createSpyObj('ConfirmationService', ['confirm']);
    component = new CloudComponent(
      {} as never,
      confirmMock as never,
      {} as never,
      {} as never,
      {} as never,
      {} as never,
      {} as never,
    );
  });

  // Open the editor on a file whose loaded state is not yet dirty.
  function openEditor(content: string, fileName = 'note.txt'): void {
    component.showFileEditor = true;
    component.newFileName = fileName;
    component.originalFileName = fileName;
    component.fileContent = content;
    component.originalFileContent = content;
  }

  function acceptLastConfirm(): void {
    const config = confirmMock.confirm.calls.mostRecent().args[0] as {
      accept: () => void;
    };
    config.accept();
  }

  it('closes without asking when the editor has no unsaved edits', () => {
    openEditor('hello');
    component.requestCloseFileEditor();
    expect(confirmMock.confirm).not.toHaveBeenCalled();
    expect(component.showFileEditor).toBeFalse();
  });

  it('asks before discarding, and only closes once Discard is confirmed', () => {
    openEditor('hello');
    component.fileContent = 'hello world'; // dirty

    component.requestCloseFileEditor();
    expect(confirmMock.confirm).toHaveBeenCalledTimes(1);
    expect(component.showFileEditor).toBeTrue(); // stays open until confirmed

    acceptLastConfirm();
    expect(component.showFileEditor).toBeFalse();
    expect(component.fileContent).toBe(''); // editor state fully reset
  });

  it('asks before discarding when only the file name changed', () => {
    openEditor('hello', 'old.txt');
    component.newFileName = 'new.txt';

    component.requestCloseFileEditor();

    expect(confirmMock.confirm).toHaveBeenCalledTimes(1);
    expect(component.showFileEditor).toBeTrue();
  });

  it('reverses a dialog dismiss and confirms when there are unsaved edits', () => {
    openEditor('hello');
    component.fileContent = 'changed';
    component.showFileEditor = false; // the dismiss already flipped visible off

    component.onFileEditorHide();

    expect(component.showFileEditor).toBeTrue(); // reopened
    expect(confirmMock.confirm).toHaveBeenCalledTimes(1);
  });

  it('lets a clean dialog dismiss close with no prompt', () => {
    openEditor('hello');
    component.showFileEditor = false;

    component.onFileEditorHide();

    expect(confirmMock.confirm).not.toHaveBeenCalled();
    expect(component.showFileEditor).toBeFalse();
  });

  it('blocks a full-page leave only while there are unsaved edits', () => {
    openEditor('hello');
    const clean = {
      preventDefault: jasmine.createSpy('preventDefault'),
      returnValue: '',
    } as unknown as BeforeUnloadEvent;
    component.warnBeforeUnload(clean);
    expect(clean.preventDefault).not.toHaveBeenCalled();

    component.fileContent = 'changed';
    const dirty = {
      preventDefault: jasmine.createSpy('preventDefault'),
      returnValue: '',
    } as unknown as BeforeUnloadEvent;
    component.warnBeforeUnload(dirty);
    expect(dirty.preventDefault).toHaveBeenCalled();
  });

  describe('folder download', () => {
    let component: CloudComponent;
    let cloudMock: jasmine.SpyObj<CloudService>;
    let toastMock: jasmine.SpyObj<UiToastService>;

    beforeEach(() => {
      jasmine.clock().uninstall();
      cloudMock = jasmine.createSpyObj<CloudService>('CloudService', [
        'getFolderArchive',
      ]);
      toastMock = jasmine.createSpyObj<UiToastService>('UiToastService', [
        'success',
        'info',
        'warn',
        'error',
      ]);
      component = new CloudComponent(
        cloudMock,
        {} as never,
        toastMock as never,
        {} as never,
        {} as never,
        {} as never,
        {} as never,
      );
      component.rootPath = '/root';
      component.currentFolder = { path: '/root/sub', name: 'sub' } as never;
    });

    it('downloads the folder as a zip archive and triggers browser download', () => {
      const mockBlob = new Blob(['zip content'], { type: 'application/zip' });
      cloudMock.getFolderArchive.and.returnValue(of(mockBlob));

      const mockAnchor = jasmine.createSpyObj<HTMLAnchorElement>(
        'HTMLAnchorElement',
        ['click'],
      );
      spyOn(document, 'createElement').and.returnValue(mockAnchor as any);
      spyOn(URL, 'createObjectURL').and.returnValue('blob:mock-url');
      spyOn(URL, 'revokeObjectURL');

      component.downloadFolder('/root/sub', 'sub');

      expect(cloudMock.getFolderArchive).toHaveBeenCalledWith('sub');
      expect(document.createElement).toHaveBeenCalledWith('a');
      expect(mockAnchor.href).toBe('blob:mock-url');
      expect(mockAnchor.download).toBe('sub.zip');
      expect(mockAnchor.click).toHaveBeenCalled();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
      expect(toastMock.info).toHaveBeenCalledWith(
        'Download started',
        '"sub.zip" is downloading.',
      );
    });

    it('surfaces folder archive backend errors using toast notifications', (done) => {
      const mockErrorResponse = {
        error: new Blob([JSON.stringify({ message: 'Rate limit exceeded' })], {
          type: 'application/json',
        }),
      };
      spyOn<any>(component, 'getErrorMessage').and.returnValue(
        'Rate limit exceeded',
      );
      cloudMock.getFolderArchive.and.returnValue(
        throwError(() => mockErrorResponse),
      );

      component.downloadFolder('/root/sub', 'sub');

      setTimeout(() => {
        expect(toastMock.error).toHaveBeenCalledWith(
          'Download failed',
          'Rate limit exceeded',
        );
        done();
      }, 50);
    });

    it('falls back to generic error message if error blob is not parseable', (done) => {
      const mockErrorResponse = {
        error: new Blob(['invalid json'], { type: 'application/json' }),
      };
      spyOn<any>(component, 'getErrorMessage').and.returnValue(
        'Request failed. Please try again.',
      );
      cloudMock.getFolderArchive.and.returnValue(
        throwError(() => mockErrorResponse),
      );

      component.downloadFolder('/root/sub', 'sub');

      setTimeout(() => {
        expect(toastMock.error).toHaveBeenCalledWith(
          'Download failed',
          'Request failed. Please try again.',
        );
        done();
      }, 50);
    });
  });
});
