import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { apiInterceptor } from './api.interceptor';
import { CartStore } from './cart-store';
import { GuestCartId } from './guest-cart-id';
import { Cart } from './models';

function cart(id: string, lines: { productId: string; quantity: number }[] = []): Cart {
  return {
    cartId: id,
    guest: !id.startsWith('customer'),
    version: 1,
    currency: 'USD',
    degraded: false,
    subtotal: 0,
    lines: lines.map((l) => ({ ...l, name: 'x', slug: 'x', imageUrl: null, unitPrice: 1, availability: 'IN_STOCK', lineTotal: l.quantity })),
  };
}

describe('CartStore', () => {
  let store: CartStore;
  let backend: HttpTestingController;
  let guestIds: GuestCartId;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([apiInterceptor])), provideHttpClientTesting()],
    });
    store = TestBed.inject(CartStore);
    backend = TestBed.inject(HttpTestingController);
    guestIds = TestBed.inject(GuestCartId);
  });

  function signedOut(): void {
    store.start().subscribe();
    backend.expectOne('/api/users/me').flush({ status: 401 }, { status: 401, statusText: 'Unauthorized' });
  }

  function signedInAsCustomer(): void {
    store.start().subscribe();
    backend.expectOne('/api/users/me').flush({ id: 'c1', role: 'CUSTOMER' });
  }

  it('a guest without a cart makes no cart calls', () => {
    signedOut();
    backend.expectNone(() => true);
    expect(store.cart()).toBeNull();
  });

  it('the first "add" creates a guest cart, remembers its id and sends it with the item', () => {
    signedOut();
    store.add('p1', 2).subscribe();

    backend.expectOne((r) => r.method === 'POST' && r.url === '/api/cart').flush(cart('guest-1'));
    expect(guestIds.get()).toBe('guest-1');

    const add = backend.expectOne('/api/cart/items');
    expect(add.request.headers.get('X-Cart-Id')).toBe('guest-1');
    expect(add.request.body).toEqual({ productId: 'p1', quantity: 2 });
    add.flush(cart('guest-1', [{ productId: 'p1', quantity: 2 }]));

    expect(store.itemCount()).toBe(2);
  });

  it('after sign-in, merges the guest cart once and forgets the guest id', () => {
    guestIds.set('guest-1');
    signedInAsCustomer();

    const merge = backend.expectOne('/api/cart/merge');
    expect(merge.request.headers.get('X-Cart-Id')).toBe('guest-1');
    merge.flush(cart('customer-cart', [{ productId: 'p1', quantity: 1 }]));
    backend.expectOne((r) => r.method === 'GET' && r.url === '/api/cart').flush(cart('customer-cart', [{ productId: 'p1', quantity: 1 }]));

    expect(guestIds.get()).toBeNull();
    expect(store.cart()?.cartId).toBe('customer-cart');
  });

  it('a customer without a guest cart just loads their cart (no merge)', () => {
    signedInAsCustomer();

    backend.expectNone('/api/cart/merge');
    backend.expectOne('/api/cart').flush(cart('customer-cart'));
    expect(store.cart()?.guest).toBe(false);
  });

  it('drops an expired guest cart id instead of failing', () => {
    guestIds.set('gone');
    signedOut();

    backend
      .expectOne('/api/cart')
      .flush({ type: '/problems/cart-not-found', status: 404 }, { status: 404, statusText: 'Not Found' });

    expect(guestIds.get()).toBeNull();
    expect(store.cart()).toBeNull();
  });

  it('starts a new guest cart when the stored one has expired while adding', () => {
    guestIds.set('gone');
    signedOut();
    backend.expectOne('/api/cart').flush(cart('gone'));

    store.add('p1').subscribe();
    backend
      .expectOne('/api/cart/items')
      .flush({ type: '/problems/cart-not-found', status: 404 }, { status: 404, statusText: 'Not Found' });
    backend.expectOne((r) => r.method === 'POST' && r.url === '/api/cart').flush(cart('guest-2'));
    const retry = backend.expectOne('/api/cart/items');
    expect(retry.request.headers.get('X-Cart-Id')).toBe('guest-2');
    retry.flush(cart('guest-2', [{ productId: 'p1', quantity: 1 }]));

    expect(guestIds.get()).toBe('guest-2');
    expect(store.itemCount()).toBe(1);
  });
});
