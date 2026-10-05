import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiInterceptor } from './api.interceptor';
import { BROWSER_LOCATION } from './login-redirect';
import { Session } from './session';

describe('Session', () => {
  let session: Session;
  let backend: HttpTestingController;
  const location = { assign: vi.fn(), pathname: '/', search: '' };

  beforeEach(() => {
    location.assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    session = TestBed.inject(Session);
    backend = TestBed.inject(HttpTestingController);
  });

  it('asks /api/users/me once, even for concurrent callers', () => {
    const answers: unknown[] = [];
    session.loadCustomer().subscribe((u) => answers.push(u));
    session.loadCustomer().subscribe((u) => answers.push(u));

    backend.expectOne('/api/users/me').flush({ id: 'c1', role: 'CUSTOMER' });
    session.loadCustomer().subscribe((u) => answers.push(u));

    backend.expectNone('/api/users/me');
    expect(answers).toHaveLength(3);
    expect(session.isCustomer()).toBe(true);
  });

  it('treats 401 as "not signed in", without redirecting to the login', () => {
    let answer: unknown = 'unset';
    session.loadCustomer().subscribe((u) => (answer = u));

    backend
      .expectOne('/api/users/me')
      .flush({ status: 401, loginUrl: '/oauth2/customer/start' }, { status: 401, statusText: 'Unauthorized' });

    expect(answer).toBeNull();
    expect(session.isCustomer()).toBe(false);
    expect(location.assign).not.toHaveBeenCalled();
  });

  it('passes a 403 on (signed in but refused): treating it as signed out would loop through the login', () => {
    let failed = false;
    session.loadAdmin().subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/admin/me').flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

    expect(failed).toBe(true);
    expect(session.admin()).toBeUndefined();
  });
});
