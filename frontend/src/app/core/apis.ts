import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Address,
  Category,
  Order,
  OrderDetails,
  OrderSummary,
  Page,
  Product,
  StorefrontHome,
  StorefrontProductPage,
  UserProfile,
  Wallet,
} from './models';

/** Public catalog: anonymous, cached by the services and by nginx/HTTP. */
@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);

  home(): Observable<StorefrontHome> {
    return this.http.get<StorefrontHome>('/api/storefront/home');
  }

  productPage(slug: string): Observable<StorefrontProductPage> {
    return this.http.get<StorefrontProductPage>(`/api/storefront/products/${encodeURIComponent(slug)}`);
  }

  categories(): Observable<Category[]> {
    return this.http.get<Category[]>('/api/categories');
  }

  products(query: { category?: string; q?: string; sort?: string; page?: number; size?: number }): Observable<Page<Product>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<Page<Product>>('/api/products', { params });
  }
}

/** The signed-in customer's own data. Ownership is enforced by the services (someone else's order is a 404). */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);

  saveAddress(address: Address): Observable<UserProfile> {
    return this.http.put<UserProfile>('/api/users/me/address', address);
  }

  wallet(): Observable<Wallet> {
    return this.http.get<Wallet>('/api/wallet');
  }

  /** The key makes a retried top-up (double click, flaky network) count once. */
  topUp(amount: number, idempotencyKey: string): Observable<Wallet> {
    return this.http.post<Wallet>('/api/wallet/top-ups', { amount }, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  orders(page = 0, size = 10): Observable<Page<OrderSummary>> {
    return this.http.get<Page<OrderSummary>>('/api/orders', { params: { page, size } });
  }

  orderDetails(id: string): Observable<OrderDetails> {
    return this.http.get<OrderDetails>(`/api/orders/${id}/details`);
  }

  /**
   * Checkout = payment, from the customer's cart. No Idempotency-Key: order-service derives one from
   * the cart's id, creation time and version, so a double click can't place two orders for the same cart.
   */
  checkout(): Observable<Order> {
    return this.http.post<Order>('/api/orders/checkout', null);
  }

  acknowledgeDelivery(id: string): Observable<Order> {
    return this.http.post<Order>(`/api/orders/${id}/acknowledge-delivery`, null);
  }
}
