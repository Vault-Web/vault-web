import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ProjectService } from './project.service';
import { SKIP_GLOBAL_ERROR_PAGE } from '../core/interceptors/token.interceptor';

describe('ProjectService', () => {
  let service: ProjectService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProjectService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('keeps independent permission failures local to the overview', () => {
    service
      .get(8)
      .subscribe({ error: (error) => expect(error.status).toBe(403) });
    const request = http.expectOne((r) => r.url.endsWith('/projects/8'));
    expect(request.request.context.get(SKIP_GLOBAL_ERROR_PAGE)).toBeTrue();
    request.flush({}, { status: 403, statusText: 'Forbidden' });
  });
  it('links paths without granting users access or copying file data', () => {
    service.link(8, '/notes.md', false).subscribe();
    const request = http.expectOne((r) =>
      r.url.endsWith('/projects/8/resources'),
    );
    expect(request.request.body).toEqual({ path: '/notes.md', folder: false });
    request.flush(null);
  });
});
