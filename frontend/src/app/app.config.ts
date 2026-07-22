import {
  ApplicationConfig,
  provideZoneChangeDetection,
  APP_INITIALIZER,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { providePrimeNG } from 'primeng/config';
import { MessageService } from 'primeng/api';
import Aura from '@primeuix/themes/aura';
import { routes } from './app.routes';
import { tokenInterceptor } from './core/interceptors/token.interceptor';
import { ServiceRegistryService } from './services/service-registry.service';
import { firstValueFrom } from 'rxjs';

export function initializeServices(registry: ServiceRegistryService) {
  return () =>
    firstValueFrom(registry.loadServices())
      .then(() => firstValueFrom(registry.checkServicesHealth()))
      .catch((err) => {
        console.error('Service registry initialization failed', err);
      });
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([tokenInterceptor])),
    providePrimeNG({
      ripple: true,
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: '.dark-theme',
        },
      },
    }),
    MessageService,
    {
      provide: APP_INITIALIZER,
      useFactory: initializeServices,
      deps: [ServiceRegistryService],
      multi: true,
    },
  ],
};
