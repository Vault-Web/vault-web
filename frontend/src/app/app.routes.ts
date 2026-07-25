import { Routes } from '@angular/router';
import { LoginComponent } from './pages/login/login.component';
import { authGuard } from './auth.guard';
import { HomeComponent } from './pages/home/home.component';
import { RegisterComponent } from './pages/register/register.component';
import { CloudComponent } from './pages/cloud/cloud.component';
import { TrashComponent } from './pages/cloud/trash/trash.component';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { PasswordManagerComponent } from './pages/password-manager/password-manager.component';
import { NotFoundComponent } from './pages/not-found/not-found.component';
import { ServerErrorComponent } from './pages/server-error/server-error.component';
import { SecurityActivityComponent } from './pages/security-activity/security-activity.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, data: { hideNavbar: true } },
  { path: '', component: HomeComponent, canActivate: [authGuard] },
  {
    path: 'dashboard',
    component: DashboardComponent,
    canActivate: [authGuard],
  },
  {
    path: 'passwords',
    component: PasswordManagerComponent,
    canActivate: [authGuard],
  },
  {
    path: 'security-activity',
    component: SecurityActivityComponent,
    canActivate: [authGuard],
  },
  { path: 'password-manager', redirectTo: 'passwords', pathMatch: 'full' },
  {
    path: 'register',
    component: RegisterComponent,
    data: { hideNavbar: true },
  },
  {
    path: 'cloud',
    component: CloudComponent,
    canActivate: [authGuard],
    canDeactivate: [
      (component: CloudComponent) =>
        component.canDeactivate ? component.canDeactivate() : true,
    ],
  },
  { path: 'cloud/trash', component: TrashComponent, canActivate: [authGuard] },
  {
    path: 'not-found',
    component: NotFoundComponent,
    data: { hideNavbar: true },
  },
  {
    path: 'error',
    component: ServerErrorComponent,
    data: { hideNavbar: true },
  },
  { path: '**', component: NotFoundComponent, data: { hideNavbar: true } },
];
