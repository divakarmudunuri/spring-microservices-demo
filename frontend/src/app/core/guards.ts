import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { LoginRedirect } from './login-redirect';
import { Session } from './session';

// Convenience only: they save a round trip to a page that would just show "sign in".
// nginx, the gateway and every service enforce access on their own.

/** Customer pages: not signed in → straight to the customer sign-in, then back here. */
export const customerGuard: CanActivateFn = (_route, state) => {
  const session = inject(Session);
  const login = inject(LoginRedirect);
  return session.loadCustomer().pipe(
    map((user) => {
      if (user?.role === 'CUSTOMER') {
        return true;
      }
      login.customer(state.url);
      return false;
    }),
    catchError(() => of(true)), // can't tell right now: let the page load and show the error itself
  );
};

/** /admin: nginx only serves these pages to admins; this covers in-app navigation. */
export const adminGuard: CanActivateFn = (_route, state) => {
  const session = inject(Session);
  const login = inject(LoginRedirect);
  return session.loadAdmin().pipe(
    map((user) => {
      if (user?.role === 'ADMIN') {
        return true;
      }
      login.admin(state.url);
      return false;
    }),
    catchError(() => of(true)),
  );
};
