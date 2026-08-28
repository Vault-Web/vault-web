import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { CloudService } from './cloud.service';

describe('CloudService Checksum', () => {
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

  it('should fetch file checksum from /files/checksum endpoint', (done) => {
    const mockResponse = {
      filePath: 'test.pdf',
      checksum:
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      algorithm: 'SHA-256',
    };

    service.getFileChecksum('test.pdf').subscribe((res) => {
      expect(res.checksum).toBe(
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      );
      expect(res.algorithm).toBe('SHA-256');
      done();
    });

    const req = httpMock.expectOne((request) =>
      request.url.endsWith('/files/checksum'),
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('path')).toBe('test.pdf');
    req.flush(mockResponse);
  });

  it('should support alternative property names (e.g. hash or sha256)', (done) => {
    const mockResponse = {
      hash: 'abc123sha256hash',
    };

    service.getFileChecksum('docs/file.txt').subscribe((res) => {
      expect(res.checksum).toBe('abc123sha256hash');
      expect(res.algorithm).toBe('SHA-256');
      done();
    });

    const req = httpMock.expectOne((request) =>
      request.url.endsWith('/files/checksum'),
    );
    expect(req.request.method).toBe('GET');
    req.flush(mockResponse);
  });
});

describe('CloudService Secure Send', () => {
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

    const req = httpMock.expectOne((r) => r.url.endsWith('/secure-sends'));
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

    const req = httpMock.expectOne((r) => r.url.endsWith('/secure-sends'));
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

    const req = httpMock.expectOne((r) => r.url.endsWith('/secure-sends'));
    expect(req.request.method).toBe('GET');
    req.flush(mockList);
  });

  it('should send DELETE request to revoke a secure send link', () => {
    service.revokeSecureSendLink('link-1').subscribe();

    const req = httpMock.expectOne((r) =>
      r.url.includes('/secure-sends/link-1'),
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('should request folders/download with path query param for folder archive', () => {
    service.getFolderArchive('test-folder').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/folders/download'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('path')).toBe('test-folder');
    req.flush(new Blob(['zip'], { type: 'application/zip' }));
  });
});
