import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, concatMap, map, of, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { GuestCartId } from './guest-cart-id';
import { Cart } from './models';
import { Session } from './session';

/**
 * The cart, for guests and customers alike. cart-service decides whose cart it is:
 * a customer's by the session (nginx forwards the token), a guest's by the X-Cart-Id header
 * (added by the interceptor while a guest id is stored). Prices are never stored here or there;
 * every response carries current prices from product-service.
 */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly http = inject(HttpClient);
  private readonly guestCartId = inject(GuestCartId);
  private readonly session = inject(Session);

  readonly cart = signal<Cart | null>(null);
  readonly itemCount = computed(() => this.cart()?.lines.reduce((sum, l) => sum + l.quantity, 0) ?? 0);

  private startup?: Observable<unknown>;

  /**
   * Once per page load: find out who this is; if a customer just signed in with a guest cart, merge it;
   * then load the cart. Pages that show the cart wait for this, so they never show the pre-merge guest cart.
   */
  start(): Observable<unknown> {
    this.startup ??= this.session.loadCustomer().pipe(
      catchError(() => of(null)),
      concatMap(() => this.mergeGuestCart().pipe(catchError(() => of(null)))),
      concatMap(() => this.refresh().pipe(catchError(() => of(null)))),
      shareReplay(1),
    );
    return this.startup;
  }

  /** Loads whichever cart this visitor has; a guest without a stored id has none yet. */
  refresh(): Observable<Cart | null> {
    if (!this.session.isCustomer() && !this.guestCartId.get()) {
      this.cart.set(null);
      return of(null);
    }
    return this.http.get<Cart>('/api/cart').pipe(
      tap((cart) => this.cart.set(cart)),
      catchError((error: unknown) => {
        if (isCartNotFound(error)) {
          // the guest cart expired (TTL) or was merged elsewhere: start over next time
          this.guestCartId.clear();
          this.cart.set(null);
          return of(null);
        }
        return throwError(() => error);
      }),
    );
  }

  add(productId: string, quantity = 1): Observable<Cart> {
    const addItem = () => this.http.post<Cart>('/api/cart/items', { productId, quantity });
    return this.ensureCart().pipe(
      switchMap(addItem),
      catchError((error: unknown) => {
        if (isCartNotFound(error) && !this.session.isCustomer()) {
          this.guestCartId.clear(); // stale guest id: make a new cart and try once more
          return this.ensureCart().pipe(switchMap(addItem));
        }
        return throwError(() => error);
      }),
      tap((cart) => this.cart.set(cart)),
    );
  }

  /** Sets a line's quantity; 0 removes it. */
  update(productId: string, quantity: number): Observable<Cart> {
    return this.http.put<Cart>(`/api/cart/items/${productId}`, { quantity }).pipe(tap((cart) => this.cart.set(cart)));
  }

  remove(productId: string): Observable<Cart> {
    return this.http.delete<Cart>(`/api/cart/items/${productId}`).pipe(tap((cart) => this.cart.set(cart)));
  }

  /**
   * Right after sign-in: fold the guest cart into the customer's cart, then forget the guest id.
   * cart-service makes this idempotent, so a repeat (two tabs, a reload) is harmless.
   */
  mergeGuestCart(): Observable<Cart | null> {
    if (!this.session.isCustomer() || !this.guestCartId.get()) {
      return of(null);
    }
    return this.http.post<Cart>('/api/cart/merge', null).pipe(
      tap((cart) => {
        this.guestCartId.clear();
        this.cart.set(cart);
      }),
    );
  }

  /** After a checkout: cart-service empties the cart once it sees ORDER_CONFIRMED (Kafka), a moment later. */
  forgetLocally(): void {
    this.cart.set(null);
  }

  private ensureCart(): Observable<void> {
    if (this.session.isCustomer() || this.guestCartId.get()) {
      return of(undefined);
    }
    return this.http.post<Cart>('/api/cart', null).pipe(
      tap((cart) => {
        this.guestCartId.set(cart.cartId);
        this.cart.set(cart);
      }),
      map(() => undefined),
    );
  }
}

function isCartNotFound(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === 404 &&
    (error.error as { type?: string } | null)?.type === '/problems/cart-not-found'
  );
}
