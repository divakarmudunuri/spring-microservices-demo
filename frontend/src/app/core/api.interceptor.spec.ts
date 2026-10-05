import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SKIP_LOGIN_REDIRECT, apiInterceptor } from './api.interceptor';
import { GuestCartId } from './guest-cart-id';
import { BROWSER_LOCATION } from './login-redirect';

describe('apiInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let guestId: string | null;
  const location = { assign: vi.fn(), pathname: '/checkout', search: '' };

  beforeEach(() => {
    guestId = null;
    location.assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: BROWSER_LOCATION, useValue: location },
        { provide: GuestCartId, useValue: { get: () => guestId, set: vi.fn(), clear: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  it('adds X-Requested-With to every API call (nginx CSRF rule)', () => {
    http.post('/api/orders/checkout', null).subscribe();
    http.get('/api/products').subscribe();

    for (const req of backend.match(() => true)) {
      expect(req.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
      req.flush({});
    }
  });

  it('leaves non-API requests alone', () => {
    http.get('/products/wireless-earbuds.svg', { responseType: 'text' }).subscribe();

    const req = backend.expectOne('/products/wireless-earbuds.svg');
    expect(req.request.headers.has('X-Requested-With')).toBe(false);
    req.flush('');
  });

  it('sends the guest cart id on cart calls only', () => {
    guestId = 'guest-123';
    http.get('/api/cart').subscribe();
    http.put('/api/cart/items/p1', { quantity: 2 }).subscribe();
    http.get('/api/orders').subscribe();

    expect(backend.expectOne('/api/cart').request.headers.get('X-Cart-Id')).toBe('guest-123');
    expect(backend.expectOne('/api/cart/items/p1').request.headers.get('X-Cart-Id')).toBe('guest-123');
    expect(backend.expectOne('/api/orders').request.headers.has('X-Cart-Id')).toBe(false);
  });

  it('sends no X-Cart-Id without a guest cart', () => {
    http.get('/api/cart').subscribe();

    expect(backend.expectOne('/api/cart').request.headers.has('X-Cart-Id')).toBe(false);
  });

  it('follows the loginUrl of a 401 ProblemDetail, coming back to the current page', () => {
    let failed = false;
    http.get('/api/orders').subscribe({ error: () => (failed = true) });

    backend
      .expectOne('/api/orders')
      .flush(
        { type: '/problems/login-required', status: 401, loginUrl: '/oauth2/customer/start' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(location.assign).toHaveBeenCalledWith('/oauth2/customer/start?rd=%2Fcheckout');
    expect(failed).toBe(true);
  });

  it('does not redirect for "am I signed in?" probes', () => {
    http.get('/api/users/me', { context: new HttpContext().set(SKIP_LOGIN_REDIRECT, true) }).subscribe({ error: () => undefined });

    backend
      .expectOne('/api/users/me')
      .flush({ status: 401, loginUrl: '/oauth2/customer/start' }, { status: 401, statusText: 'Unauthorized' });

    expect(location.assign).not.toHaveBeenCalled();
  });

  it('does not redirect on a 401 without a loginUrl', () => {
    http.get('/api/orders').subscribe({ error: () => undefined });

    backend.expectOne('/api/orders').flush({ status: 401 }, { status: 401, statusText: 'Unauthorized' });

    expect(location.assign).not.toHaveBeenCalled();
  });
});
