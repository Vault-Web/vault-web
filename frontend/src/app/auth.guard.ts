import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './services/auth.service';
import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export const authGuard: CanActivateFn = async (_route, _state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isLoggedInLocally()) {
    router.navigate(['/login']);
    return false;
  }

  const isValidOnServer = await firstValueFrom(
    authService.validateTokenWithServer(),
  );
  if (!isValidOnServer) {
    router.navigate(['/login']);
    return false;
  }

  return true;
};
