import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OrderDetails } from './models';
import { stillChanging } from '../pages/order-detail';
import { BROWSER_LOCATION, LoginRedirect } from './login-redirect';
import { problemMessage } from './problem';

describe('LoginRedirect', () => {
  const location = { assign: vi.fn(), pathname: '/product/mug', search: '?x=1' };
  let login: LoginRedirect;

  beforeEach(() => {
    location.assign.mockReset();
    TestBed.configureTestingModule({ providers: [{ provide: BROWSER_LOCATION, useValue: location }] });
    login = TestBed.inject(LoginRedirect);
  });

  it('starts the customer sign-in and comes back to the current page by default', () => {
    login.customer();
    expect(location.assign).toHaveBeenCalledWith('/oauth2/customer/start?rd=%2Fproduct%2Fmug%3Fx%3D1');
  });

  it('starts the admin sign-in for /admin', () => {
    login.admin();
    expect(location.assign).toHaveBeenCalledWith('/oauth2/admin/start?rd=%2Fadmin');
  });

  it('signs out through oauth2-proxy', () => {
    login.signOutCustomer();
    login.signOutAdmin();
    expect(location.assign).toHaveBeenNthCalledWith(1, '/oauth2/customer/sign_out?rd=%2F');
    expect(location.assign).toHaveBeenNthCalledWith(2, '/oauth2/admin/sign_out?rd=%2F');
  });
});

describe('problemMessage', () => {
  const error = (status: number, body: unknown) => new HttpErrorResponse({ status, error: body });

  it('shows the ProblemDetail title and detail', () => {
    expect(problemMessage(error(409, { title: 'Out of stock', detail: 'Only 0 left of Keyboard' }))).toBe(
      'Out of stock: Only 0 left of Keyboard',
    );
  });

  it('does not repeat a title that the detail already contains', () => {
    expect(problemMessage(error(500, { title: 'Injected failure', detail: 'Injected failure (chaos)' }))).toBe(
      'Injected failure (chaos)',
    );
  });

  it('has readable fallbacks for network errors, rate limits and server errors', () => {
    expect(problemMessage(error(0, null))).toContain("Can't reach");
    expect(problemMessage(error(429, ''))).toContain('Too many requests');
    expect(problemMessage(error(502, '<html>'))).toContain('having trouble');
  });
});

describe('order page polling (stillChanging)', () => {
  const order = (status: OrderDetails['status'], timeline: string[], unavailable: string[] = []): OrderDetails =>
    ({
      status,
      unavailableSections: unavailable,
      tracking: { currentStatus: timeline.at(-1) ?? '', timeline: timeline.map((s) => ({ status: s, source: 'x', occurredAt: '', details: null })) },
    }) as unknown as OrderDetails;

  it('keeps refreshing while the order moves through fulfillment and shipping', () => {
    expect(stillChanging(order('IN_FULFILLMENT', ['ORDER_CONFIRMED']))).toBe(true);
  });

  it('keeps refreshing until tracking (fed by Kafka) shows the closing event', () => {
    expect(stillChanging(order('COMPLETED', ['ORDER_DELIVERED']))).toBe(true);
    expect(stillChanging(order('COMPLETED', ['ORDER_DELIVERED', 'DELIVERY_ACKNOWLEDGED']))).toBe(false);
  });

  it('stops when tracking is unavailable rather than polling forever', () => {
    expect(stillChanging(order('DELIVERED', [], ['tracking']))).toBe(false);
  });
});
