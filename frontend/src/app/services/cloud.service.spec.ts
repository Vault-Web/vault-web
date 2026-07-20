import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { CloudService } from './cloud.service';

describe('CloudService Storage Quota', () => {
  let service: CloudService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CloudService],
    });

    service = TestBed.inject(CloudService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should fetch storage quota usage from /storage/quota endpoint', (done) => {
    const mockQuota = {
      usedBytes: 1048576,
      totalBytes: 1073741824,
    };

    service.getStorageQuota().subscribe((res) => {
      expect(res.usedBytes).toBe(1048576);
      expect(res.totalBytes).toBe(1073741824);
      done();
    });

    const req = httpMock.expectOne(`${service.apiUrl}/storage/quota`);
    expect(req.request.method).toBe('GET');
    req.flush(mockQuota);
  });

  it('should fallback property names gracefully if storage quota format differs', (done) => {
    const mockQuotaAlt = {
      used: 2048,
      total: 8192,
    };

    service.getStorageQuota().subscribe((res) => {
      expect(res.usedBytes).toBe(2048);
      expect(res.totalBytes).toBe(8192);
      done();
    });

    const req = httpMock.expectOne(`${service.apiUrl}/storage/quota`);
    expect(req.request.method).toBe('GET');
    req.flush(mockQuotaAlt);
  });

  it('should handle error path when storage quota endpoint fails', (done) => {
    service.getStorageQuota().subscribe({
      next: () => fail('expected error, but got response'),
      error: (err) => {
        expect(err.status).toBe(404);
        done();
      },
    });

    const req = httpMock.expectOne(`${service.apiUrl}/storage/quota`);
    expect(req.request.method).toBe('GET');
    req.flush('Not Found', { status: 404, statusText: 'Not Found' });
  });
});
