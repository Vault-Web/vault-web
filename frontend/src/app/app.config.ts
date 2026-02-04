import {
  ApplicationConfig,
  APP_INITIALIZER,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { tokenInterceptor } from './core/interceptors/token.interceptor';
import { authInitializer } from './core/init/auth.initializer';
import { AuthService } from '../app/services/auth.service';
import { Router } from '@angular/router';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([tokenInterceptor])),

    {
      provide: APP_INITIALIZER,
      useFactory: authInitializer,
      deps: [AuthService, Router],
      multi: true,
    },
  ],
};
