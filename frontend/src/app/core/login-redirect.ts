import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, inject } from '@angular/core';

/** The bit of window.location we use; a token so tests can swap it. */
export interface BrowserLocation {
  assign(url: string): void;
  readonly pathname: string;
  readonly search: string;
}

export const BROWSER_LOCATION = new InjectionToken<BrowserLocation>('BROWSER_LOCATION', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).location,
});

/**
 * Sign-in and sign-out are full-page trips through nginx + oauth2-proxy, not app routes:
 * the app never sees a token, only the HttpOnly session cookie that comes back.
 */
@Injectable({ providedIn: 'root' })
export class LoginRedirect {
  private readonly location = inject(BROWSER_LOCATION);

  /** "Sign in with Google" (dev: the dev-idp's customer issuer). */
  customer(returnTo = this.currentUrl()): void {
    this.follow('/oauth2/customer/start', returnTo);
  }

  /** Okta (dev: the dev-idp's admin issuer). */
  admin(returnTo = '/admin'): void {
    this.follow('/oauth2/admin/start', returnTo);
  }

  /** Goes to a login URL handed out by nginx in a 401 ProblemDetail, coming back to `returnTo`. */
  follow(loginUrl: string, returnTo = this.currentUrl()): void {
    const separator = loginUrl.includes('?') ? '&' : '?';
    this.location.assign(`${loginUrl}${separator}rd=${encodeURIComponent(returnTo)}`);
  }

  signOutCustomer(): void {
    this.location.assign('/oauth2/customer/sign_out?rd=%2F');
  }

  signOutAdmin(): void {
    this.location.assign('/oauth2/admin/sign_out?rd=%2F');
  }

  currentUrl(): string {
    return this.location.pathname + this.location.search;
  }
}
