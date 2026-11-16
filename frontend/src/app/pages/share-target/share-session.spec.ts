import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { FormBuilder } from '@angular/forms';
import { tokenInterceptor } from '../../core/interceptors/token.interceptor';
import { NetworkStatusService } from '../../core/services/network-status.service';
import { AuthService } from '../../services/auth.service';
import { CloudService } from '../../services/cloud.service';
import { LoginComponent } from '../login/login.component';
import { environment } from '../../../environments/environment';

describe('Share draft session recovery HTTP contract', () => {
  const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  let router: jasmine.SpyObj<Router>;
  let http: HttpTestingController;
  let auth: AuthService;
  beforeEach(() => {
    router = jasmine.createSpyObj('router', ['navigate'], {
      url: '/share-target?id=' + id,
    });
    router.navigate.and.resolveTo(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([tokenInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        {
          provide: NetworkStatusService,
          useValue: { showBackendUnavailable: () => undefined },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.saveToken('old-token');
    auth.saveUsername('owner');
  });
  afterEach(() => {
    http.verify();
    localStorage.removeItem('token');
    localStorage.removeItem('username');
  });

  it('preserves the return ID after real upload 401 and refresh failure, then returns after login', () => {
    let failed = false;
    TestBed.inject(CloudService)
      .uploadFile('/owner', new File(['data'], 'shared.txt'), undefined, true)
      .subscribe({
        error: () => {
          failed = true;
        },
      });
    http
      .expectOne((req) => req.url.endsWith('/files/upload'))
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    http
      .expectOne(environment.mainApiUrl + '/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    // Both the refresh endpoint and original request can invoke the existing logout path.
    const logouts = http.match(environment.mainApiUrl + '/auth/logout');
    expect(logouts.length).toBeGreaterThan(0);
    logouts.forEach((request) => request.flush({}));
    expect(failed).toBeTrue();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnShare: id },
    });
    const login = new LoginComponent(
      new FormBuilder(),
      auth,
      {
        snapshot: { queryParamMap: convertToParamMap({ returnShare: id }) },
      } as ActivatedRoute,
      router,
    );
    login.ngOnInit();
    login.loginForm.setValue({ username: 'owner', password: 'test-only' });
    login.onSubmit();
    http
      .expectOne(environment.mainApiUrl + '/auth/login')
      .flush({ token: 'new-token' });
    expect(router.navigate).toHaveBeenCalledWith(['/share-target'], {
      queryParams: { id },
    });
  });
});
