import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

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
  private services: ServiceManifest[] = [];
  private isLoaded = false;

  constructor(private http: HttpClient) {}

  loadServices(): Observable<ServiceManifest[]> {
    const manifests = [
      '/cloud-service.json',
      '/password-manager-service.json',
      '/habits-service.json',
    ];

    const requests = manifests.map((url) =>
      this.http.get<ServiceManifest>(url).pipe(
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
            loadedServices.push(res);
          }
        }
        this.services = loadedServices;
        this.isLoaded = true;
        return this.services;
      }),
    );
  }

  checkServicesHealth(): Observable<ServiceManifest[]> {
    if (this.services.length === 0) {
      return of([]);
    }

    const checks = this.services.map((service) =>
      this.http.get(service.healthEndpoint).pipe(
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
        return this.services;
      }),
    );
  }

  getServices(): ServiceManifest[] {
    return this.services;
  }

  getServiceByName(name: string): ServiceManifest | undefined {
    return this.services.find((s) => s.name === name);
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
      typeof candidate['healthEndpoint'] === 'string'
    );
  }
}
