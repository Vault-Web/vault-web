import { bootstrapApplication } from '@angular/platform-browser';
import { provideAnimations } from '@angular/platform-browser/animations';

import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { isDevMode } from '@angular/core';

bootstrapApplication(AppComponent, {
  providers: [provideAnimations(), ...appConfig.providers!],
}).catch((err) => console.error(err));

if (!isDevMode() && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/vault-worker.js', { updateViaCache: 'none' })
      .catch(() =>
        console.warn('App installation and share receiving are unavailable.'),
      );
  });
}
