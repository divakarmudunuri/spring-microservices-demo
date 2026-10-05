import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, of, shareReplay, tap, throwError } from 'rxjs';
import { SKIP_LOGIN_REDIRECT } from './api.interceptor';
import { UserProfile } from './models';

/**
 * Who is signed in. The app holds no token: the session is an HttpOnly cookie set by oauth2-proxy,
 * so "am I signed in?" is simply GET /api/users/me (customers) or GET /api/admin/me (admins).
 * A 401 means "not signed in"; it does not trigger a redirect.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  /** undefined = not asked yet, null = not signed in. */
  readonly customer = signal<UserProfile | null | undefined>(undefined);
  readonly admin = signal<UserProfile | null | undefined>(undefined);

  readonly isCustomer = computed(() => this.customer()?.role === 'CUSTOMER');
  readonly isAdmin = computed(() => this.admin()?.role === 'ADMIN');

  private customerRequest?: Observable<UserProfile | null>;
  private adminRequest?: Observable<UserProfile | null>;

  /** Asks once and remembers; concurrent callers (app start, a guard) share the same request. */
  loadCustomer(): Observable<UserProfile | null> {
    const known = this.customer();
    if (known !== undefined) {
      return of(known);
    }
    this.customerRequest ??= this.whoAmI('/api/users/me').pipe(
      tap((user) => this.customer.set(user)),
      finalize(() => (this.customerRequest = undefined)),
      shareReplay(1),
    );
    return this.customerRequest;
  }

  loadAdmin(): Observable<UserProfile | null> {
    const known = this.admin();
    if (known !== undefined) {
      return of(known);
    }
    this.adminRequest ??= this.whoAmI('/api/admin/me').pipe(
      tap((user) => this.admin.set(user)),
      finalize(() => (this.adminRequest = undefined)),
      shareReplay(1),
    );
    return this.adminRequest;
  }

  /** After the profile changed (e.g. a new address). */
  setCustomer(user: UserProfile): void {
    this.customer.set(user);
  }

  private whoAmI(url: string): Observable<UserProfile | null> {
    return this.http.get<UserProfile>(url, { context: new HttpContext().set(SKIP_LOGIN_REDIRECT, true) }).pipe(
      // Only 401 means "not signed in". A 403 (e.g. a suspended account) is signed in but refused:
      // treating it as "sign in again" would loop through the identity provider forever.
      catchError((error: unknown) =>
        error instanceof HttpErrorResponse && error.status === 401 ? of(null) : throwError(() => error),
      ),
    );
  }
}
