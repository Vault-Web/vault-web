import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { CloudService } from './cloud.service';

describe('CloudService', () => {
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

  describe('Storage Quota', () => {
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

  describe('Secure Send', () => {
    it('should send POST request to create a secure send link with ISO instant expiresAt', () => {
      const mockResponse = {
        id: 'share-123',
        filePath: '/document.pdf',
        fileName: 'document.pdf',
        url: 'http://localhost/share/token123',
        expiresAt: '2026-07-21T12:00:00Z',
        passwordProtected: true,
        revoked: false,
      };

      service
        .createSecureSendLink('/document.pdf', 1440, 'secret')
        .subscribe((res) => {
          expect(res.id).toBe('share-123');
          expect(res.fileName).toBe('document.pdf');
          expect(res.hasPassword).toBeTrue();
          expect(res.shareUrl).toBe('http://localhost/share/token123');
        });

      const req = httpMock.expectOne(`${service.apiUrl}/secure-sends`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.filePath).toBe('/document.pdf');
      expect(req.request.body.expiresAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(req.request.body.password).toBe('secret');
      req.flush(mockResponse);
    });

    it('should map passwordProtected true from response to hasPassword true', () => {
      const mockResponse = {
        id: 'share-456',
        filePath: '/secret.txt',
        fileName: 'secret.txt',
        url: 'http://localhost/share/token456',
        expiresAt: '2026-07-21T12:00:00Z',
        passwordProtected: true,
        revoked: false,
      };

      service.listSecureSendLinks().subscribe((links) => {
        expect(links.length).toBe(1);
        expect(links[0].hasPassword).toBeTrue();
        expect(links[0].isRevoked).toBeFalse();
      });

      const req = httpMock.expectOne(`${service.apiUrl}/secure-sends`);
      expect(req.request.method).toBe('GET');
      req.flush([mockResponse]);
    });

    it('should list active secure send links', () => {
      const mockList = [
        {
          id: 'link-1',
          filePath: '/test.png',
          expiresAt: '2026-07-22T00:00:00Z',
          passwordProtected: false,
          revoked: false,
        },
      ];

      service.listSecureSendLinks().subscribe((links) => {
        expect(links.length).toBe(1);
        expect(links[0].id).toBe('link-1');
        expect(links[0].hasPassword).toBeFalse();
      });

      const req = httpMock.expectOne(`${service.apiUrl}/secure-sends`);
      expect(req.request.method).toBe('GET');
      req.flush(mockList);
    });

    it('should send DELETE request to revoke a secure send link', () => {
      service.revokeSecureSendLink('link-1').subscribe();

      const req = httpMock.expectOne(`${service.apiUrl}/secure-sends/link-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });
});
