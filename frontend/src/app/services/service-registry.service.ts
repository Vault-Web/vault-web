import { Injectable } from '@angular/core';
import { HttpClient, HttpBackend } from '@angular/common/http';
import { Observable, BehaviorSubject, forkJoin, of } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';

export interface ServiceManifest {
  name: string;
  displayName: string;
  icon: string;
  route: string;
  baseUrl: string;
  apiUrl: string;
  healthEndpoint: string;
  requiredScopes?: string[];
  tokenForwarding?: {
    enabled: boolean;
    type?: 'header' | 'cookie' | 'query';
    name?: string;
  };
  isAvailable?: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class ServiceRegistryService {
  private servicesSubject = new BehaviorSubject<ServiceManifest[]>([]);
  public services$ = this.servicesSubject.asObservable();

  private isLoaded = false;
  private rawHttpClient: HttpClient;

  constructor(
    private http: HttpClient,
    handler: HttpBackend,
  ) {
    // Uses un-intercepted HttpClient to bypass tokenInterceptor and error handling banners during health checks
    this.rawHttpClient = new HttpClient(handler);
  }

  resolveUrl(url: string): string {
    if (!url) return '';
    if (url.startsWith('/')) {
      if (typeof window !== 'undefined' && window.location?.origin) {
        return `${window.location.origin}${url}`;
      }
      return url;
    }
    if (typeof window !== 'undefined' && window.location?.hostname) {
      const currentHost = window.location.hostname;
      const currentProtocol = window.location.protocol;
      let resolved = url
        .replace('{host}', currentHost)
        .replace('localhost', currentHost);
      if (currentProtocol === 'https:' && resolved.startsWith('http:')) {
        resolved = resolved.replace('http:', 'https:');
      }
      return resolved;
    }
    return url;
  }

  loadServices(): Observable<ServiceManifest[]> {
    const manifests = [
      '/cloud-service.json',
      '/password-manager-service.json',
      '/habits-service.json',
    ];

    const requests = manifests.map((url) =>
      this.rawHttpClient.get<ServiceManifest>(url).pipe(
        timeout(3000),
        catchError((err) => {
          console.warn(`Failed to load service manifest from ${url}`, err);
          return of(null);
        }),
      ),
    );

    return forkJoin(requests).pipe(
      map((results) => {
        const loadedServices: ServiceManifest[] = [];
        for (const res of results) {
          if (res && this.isValidManifest(res)) {
            const resolved: ServiceManifest = {
              ...res,
              baseUrl: this.resolveUrl(res.baseUrl),
              apiUrl: this.resolveUrl(res.apiUrl),
              healthEndpoint: this.resolveUrl(res.healthEndpoint),
              route: this.resolveUrl(res.route),
            };
            loadedServices.push(resolved);
          }
        }
        this.servicesSubject.next(loadedServices);
        this.isLoaded = true;
        return loadedServices;
      }),
    );
  }

  checkServicesHealth(): Observable<ServiceManifest[]> {
    const currentServices = this.servicesSubject.getValue();
    if (currentServices.length === 0) {
      return of([]);
    }

    const checks = currentServices.map((service) =>
      this.rawHttpClient.get(service.healthEndpoint).pipe(
        timeout(3000),
        map(() => {
          service.isAvailable = true;
          return service;
        }),
        catchError((err) => {
          console.warn(`Service ${service.name} is unhealthy`, err);
          service.isAvailable = false;
          return of(service);
        }),
      ),
    );

    return forkJoin(checks).pipe(
      map(() => {
        this.servicesSubject.next([...currentServices]);
        return currentServices;
      }),
    );
  }

  getServices(): ServiceManifest[] {
    return this.servicesSubject.getValue();
  }

  getServiceByName(name: string): ServiceManifest | undefined {
    return this.servicesSubject.getValue().find((s) => s.name === name);
  }

  private isValidManifest(manifest: unknown): boolean {
    if (!manifest || typeof manifest !== 'object') {
      return false;
    }
    const candidate = manifest as Record<string, unknown>;
    return (
      typeof candidate['name'] === 'string' &&
      typeof candidate['displayName'] === 'string' &&
      typeof candidate['icon'] === 'string' &&
      typeof candidate['route'] === 'string' &&
      typeof candidate['baseUrl'] === 'string' &&
      typeof candidate['apiUrl'] === 'string' &&
      typeof candidate['healthEndpoint'] === 'string'
    );
  }
}
