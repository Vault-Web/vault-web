import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

export function authInitializer(authService: AuthService, router: Router) {
  return () =>
    authService.validateToken().subscribe((isValid) => {
      if (!isValid) {
        authService.logout();
        router.navigate(['/login']);
      }
    });
}
