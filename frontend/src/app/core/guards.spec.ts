import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { Observable, firstValueFrom, isObservable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { adminGuard, customerGuard } from './guards';
import { LoginRedirect } from './login-redirect';
import { UserProfile } from './models';
import { Session } from './session';

const customer: UserProfile = { id: 'c1', email: 'customer@demo.local', fullName: 'Demo Customer', role: 'CUSTOMER', status: 'ACTIVE', defaultAddress: null };
const admin: UserProfile = { ...customer, id: 'a1', role: 'ADMIN' };

describe('route guards', () => {
  const login = { customer: vi.fn(), admin: vi.fn() };
  let customerAnswer: Observable<UserProfile | null>;
  let adminAnswer: Observable<UserProfile | null>;

  beforeEach(() => {
    login.customer.mockReset();
    login.admin.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: LoginRedirect, useValue: login },
        { provide: Session, useValue: { loadCustomer: () => customerAnswer, loadAdmin: () => adminAnswer } },
      ],
    });
  });

  async function run(guard: typeof customerGuard, url: string): Promise<unknown> {
    const result = TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );
    return isObservable(result) ? firstValueFrom(result) : result;
  }

  it('lets a signed-in customer through', async () => {
    customerAnswer = of(customer);
    expect(await run(customerGuard, '/checkout')).toBe(true);
    expect(login.customer).not.toHaveBeenCalled();
  });

  it('sends a visitor to the customer sign-in, returning to the page they wanted', async () => {
    customerAnswer = of(null);
    expect(await run(customerGuard, '/orders/42')).toBe(false);
    expect(login.customer).toHaveBeenCalledWith('/orders/42');
  });

  it('lets the page load (and show the error) when the session check itself fails', async () => {
    customerAnswer = throwError(() => new Error('503'));
    expect(await run(customerGuard, '/orders')).toBe(true);
  });

  it('lets an admin into /admin', async () => {
    adminAnswer = of(admin);
    expect(await run(adminGuard, '/admin/orders')).toBe(true);
  });

  it('sends a non-admin to the admin sign-in', async () => {
    adminAnswer = of(null);
    expect(await run(adminGuard, '/admin/inventory')).toBe(false);
    expect(login.admin).toHaveBeenCalledWith('/admin/inventory');
  });

  it('does not treat a customer profile as an admin', async () => {
    adminAnswer = of(customer);
    expect(await run(adminGuard, '/admin')).toBe(false);
  });
});
