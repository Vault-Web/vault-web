import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import {
  ServiceRegistryService,
  ServiceManifest,
} from './service-registry.service';

describe('ServiceRegistryService', () => {
  let service: ServiceRegistryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ServiceRegistryService],
    });
    service = TestBed.inject(ServiceRegistryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should load service manifests dynamically', () => {
    const mockCloudManifest: ServiceManifest = {
      name: 'cloud',
      displayName: 'Cloud',
      icon: 'pi-cloud',
      route: '/cloud',
      baseUrl: 'http://localhost:8090',
      apiUrl: 'http://localhost:8090/api',
      healthEndpoint: 'http://localhost:8090/api/health',
    };

    const mockPMManifest: ServiceManifest = {
      name: 'passwords',
      displayName: 'Password Manager',
      icon: 'pi-key',
      route: '/passwords',
      baseUrl: 'http://localhost:8091',
      apiUrl: 'http://localhost:8091/api',
      healthEndpoint: 'http://localhost:8091/api/health',
    };

    const mockHabitsManifest: ServiceManifest = {
      name: 'habits',
      displayName: 'Habits',
      icon: 'pi-calendar-plus',
      route: '/habits',
      baseUrl: 'http://localhost:8092',
      apiUrl: 'http://localhost:8092/api',
      healthEndpoint: 'http://localhost:8092/api/health',
    };

    service.loadServices().subscribe((loaded) => {
      expect(loaded.length).toBe(3);
      expect(loaded[0].name).toBe('cloud');
      expect(loaded[1].name).toBe('passwords');
      expect(loaded[2].name).toBe('habits');
    });

    const reqs = [
      httpMock.expectOne('/cloud-service.json'),
      httpMock.expectOne('/password-manager-service.json'),
      httpMock.expectOne('/habits-service.json'),
    ];

    reqs[0].flush(mockCloudManifest);
    reqs[1].flush(mockPMManifest);
    reqs[2].flush(mockHabitsManifest);
  });

  it('should handle manifest load failure gracefully by filtering them out', () => {
    service.loadServices().subscribe((loaded) => {
      expect(loaded.length).toBe(1);
      expect(loaded[0].name).toBe('passwords');
    });

    const reqs = [
      httpMock.expectOne('/cloud-service.json'),
      httpMock.expectOne('/password-manager-service.json'),
      httpMock.expectOne('/habits-service.json'),
    ];

    reqs[0].flush('Not Found', { status: 404, statusText: 'Not Found' });
    reqs[1].flush({
      name: 'passwords',
      displayName: 'Password Manager',
      icon: 'pi-key',
      route: '/passwords',
      baseUrl: 'http://localhost:8091',
      healthEndpoint: 'http://localhost:8091/api/health',
    });
    reqs[2].flush('Server Error', {
      status: 500,
      statusText: 'Internal Error',
    });
  });

  it('should update availability states during health checks', () => {
    const services: ServiceManifest[] = [
      {
        name: 'cloud',
        displayName: 'Cloud',
        icon: 'pi-cloud',
        route: '/cloud',
        baseUrl: 'http://localhost:8090',
        apiUrl: 'http://localhost:8090/api',
        healthEndpoint: 'http://localhost:8090/api/health',
      },
      {
        name: 'passwords',
        displayName: 'Password Manager',
        icon: 'pi-key',
        route: '/passwords',
        baseUrl: 'http://localhost:8091',
        apiUrl: 'http://localhost:8091/api',
        healthEndpoint: 'http://localhost:8091/api/health',
      },
    ];

    (service as any).services = services;

    service.checkServicesHealth().subscribe((checked) => {
      expect(checked[0].isAvailable).toBeTrue();
      expect(checked[1].isAvailable).toBeFalse();
    });

    const reqCloud = httpMock.expectOne('http://localhost:8090/api/health');
    const reqPM = httpMock.expectOne('http://localhost:8091/api/health');

    reqCloud.flush({ status: 'UP' });
    reqPM.flush('Service Unavailable', {
      status: 503,
      statusText: 'Service Unavailable',
    });
  });
});
