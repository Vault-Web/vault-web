import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { of, throwError, Subject } from 'rxjs';
import { ConfirmationService } from 'primeng/api';
import { CloudComponent } from './cloud.component';
import { CloudService } from '../../services/cloud.service';
import { UiToastService } from '../../core/services/ui-toast.service';
import { ScanJobDto } from '../../models/dtos/ScanJobDto';

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

    jasmine.clock().tick(1200);
    expect(component.scanning).toBeTrue();

    jasmine.clock().tick(1200);
    expect(component.scanning).toBeFalse();
    expect(component.scanJob?.status).toBe('COMPLETED');
    expect(component.scanInfectedFindings.length).toBe(1);
    expect(component.scanNoThreats).toBeFalse();

    const callsSoFar = cloudMock.getScanJob.calls.count();
    jasmine.clock().tick(6000);
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
    cloudMock.getScanJob.and.returnValues(
      throwError(() => ({ status: 429 })),
      of({ ...runningJob, status: 'COMPLETED', findings: [] }),
    );

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);

    expect(component.scanning).toBeTrue();
    expect(component.scanError).toBeUndefined();

    jasmine.clock().tick(5000);
    expect(component.scanning).toBeFalse();
    expect(component.scanError).toBeUndefined();
    expect(component.scanJob?.status).toBe('COMPLETED');
  });

  it('gives up on a persistently rate-limited scan without claiming it failed', () => {
    cloudMock.startFolderScan.and.returnValue(of({ ...runningJob }));
    cloudMock.getScanJob.and.returnValue(throwError(() => ({ status: 429 })));

    component.scanCurrentFolder();
    jasmine.clock().tick(1200);
    jasmine.clock().tick(5000 * 5);

    expect(component.scanning).toBeFalse();
    expect(component.scanError).toContain('may still be running');
    expect(cloudMock.getScanJob).toHaveBeenCalledTimes(6);
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
    cloudMock.getScanJob.and.returnValue(of({ ...runningJob }));

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

    component.scanCurrentFolder();
    component.onScanDialogHide();
    start$.next({ ...runningJob });
    start$.complete();
    jasmine.clock().tick(6000);

    expect(cloudMock.getScanJob).not.toHaveBeenCalled();
  });
});

describe('CloudComponent Storage Quota', () => {
  let component: CloudComponent;
  let fixture: ComponentFixture<CloudComponent>;
  let cloudServiceSpy: jasmine.SpyObj<CloudService>;
  let toastSpy: jasmine.SpyObj<UiToastService>;

  beforeEach(async () => {
    cloudServiceSpy = jasmine.createSpyObj('CloudService', [
      'getRootFolder',
      'getFolderContent',
      'getStorageQuota',
      'uploadFile',
      'deleteFile',
    ]);
    toastSpy = jasmine.createSpyObj('UiToastService', [
      'success',
      'error',
      'info',
    ]);

    cloudServiceSpy.getRootFolder.and.returnValue(
      of({
        name: 'Root',
        path: '/',
        folders: [],
        files: [],
        lastModifiedAt: 0,
      } as any),
    );
    cloudServiceSpy.getFolderContent.and.returnValue(
      of({
        content: [],
        totalElements: 0,
        totalPages: 1,
        pageNumber: 0,
        pageSize: 50,
      } as any),
    );
    cloudServiceSpy.getStorageQuota.and.returnValue(
      of({ usedBytes: 500, totalBytes: 1000 }),
    );

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
        {
          provide: Router,
          useValue: { navigate: jasmine.createSpy('navigate') },
        },
        ConfirmationService,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CloudComponent);
    component = fixture.componentInstance;
  });

  it('should initialize and load storage quota', () => {
    fixture.detectChanges();
    expect(component.storageQuota).toEqual({
      usedBytes: 500,
      totalBytes: 1000,
    });
    expect(component.quotaPercentage).toBe(50);
    expect(component.usedSpaceLabel).toBe('500 Bytes');
    expect(component.totalSpaceLabel).toBe('1000 Bytes');
    expect(component.quotaStatusClass).toBe('quota-normal');
  });

  it('should set warning and danger status classes based on quota percentage', () => {
    component.storageQuota = { usedBytes: 800, totalBytes: 1000 };
    expect(component.quotaPercentage).toBe(80);
    expect(component.quotaStatusClass).toBe('quota-warning');

    component.storageQuota = { usedBytes: 950, totalBytes: 1000 };
    expect(component.quotaPercentage).toBe(95);
    expect(component.quotaStatusClass).toBe('quota-danger');
  });

  it('should detect quota-exceeded errors correctly', () => {
    expect(component.isQuotaExceededError({ status: 413 })).toBeTrue();
    expect(component.isQuotaExceededError({ status: 507 })).toBeTrue();
    expect(
      component.isQuotaExceededError({
        error: { message: 'Storage quota exceeded' },
      }),
    ).toBeTrue();
    expect(
      component.isQuotaExceededError({
        message: 'User is out of space',
      }),
    ).toBeTrue();
    expect(
      component.isQuotaExceededError({
        status: 400,
        message: 'Invalid format',
      }),
    ).toBeFalse();
  });

  it('should hide storage quota widget when storage quota endpoint fails', () => {
    cloudServiceSpy.getStorageQuota.and.returnValue(
      throwError(() => new Error('Quota endpoint unavailable')),
    );
    component.loadStorageQuota();
    expect(component.storageQuota).toBeUndefined();
  });
});
