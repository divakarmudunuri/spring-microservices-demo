import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { GuestCartId } from './guest-cart-id';
import { LoginRedirect } from './login-redirect';
import { ProblemDetail } from './models';

/** Set on requests that only ask "am I signed in?": a 401 there is an answer, not a reason to redirect. */
export const SKIP_LOGIN_REDIRECT = new HttpContextToken<boolean>(() => false);

/**
 * Every call to our API:
 * - adds `X-Requested-With: XMLHttpRequest`. nginx rejects state-changing API calls without it
 *   (CSRF guard: a cross-site form or image can't set a custom header);
 * - adds `X-Cart-Id` to cart calls while a guest cart exists (and only to cart calls: the id is a secret);
 * - on a 401 ProblemDetail with a `loginUrl` (nginx: "sign in first"), starts that sign-in and comes back here.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  const guestCartId = inject(GuestCartId);
  const login = inject(LoginRedirect);

  let headers = req.headers.set('X-Requested-With', 'XMLHttpRequest');
  const cartId = guestCartId.get();
  if (cartId && isCartCall(req.url) && !headers.has('X-Cart-Id')) {
    headers = headers.set('X-Cart-Id', cartId);
  }

  return next(req.clone({ headers })).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !req.context.get(SKIP_LOGIN_REDIRECT)) {
        const loginUrl = (error.error as ProblemDetail | null)?.loginUrl;
        if (loginUrl) {
          login.follow(loginUrl);
        }
      }
      return throwError(() => error);
    }),
  );
};

function isCartCall(url: string): boolean {
  return url === '/api/cart' || url.startsWith('/api/cart/') || url.startsWith('/api/cart?');
}
