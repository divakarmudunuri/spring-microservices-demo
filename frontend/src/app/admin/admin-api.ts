import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Inventory, OrderDetails, OrderSummary, Page, PaymentsReport, Shipment } from '../core/models';

/** Admin-only endpoints (/api/admin/**): nginx needs an Okta session, the gateway and services need ADMIN. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  orders(filter: { status?: string; userId?: string; page?: number; size?: number }): Observable<Page<OrderSummary>> {
    return this.http.get<Page<OrderSummary>>('/api/admin/orders', { params: params(filter) });
  }

  orderDetails(id: string): Observable<OrderDetails> {
    return this.http.get<OrderDetails>(`/api/admin/orders/${id}/details`);
  }

  payments(filter: { status?: string; page?: number; size?: number }): Observable<PaymentsReport> {
    return this.http.get<PaymentsReport>('/api/admin/payments', { params: params(filter) });
  }

  shipments(filter: { status?: string; page?: number; size?: number }): Observable<Page<Shipment>> {
    return this.http.get<Page<Shipment>>('/api/admin/shipments', { params: params(filter) });
  }

  inventory(): Observable<Inventory> {
    return this.http.get<Inventory>('/api/admin/inventory');
  }

  restock(productId: string, quantity: number, note: string): Observable<{ productId: string; quantityOnHand: number }> {
    return this.http.post<{ productId: string; quantityOnHand: number }>(
      `/api/admin/inventory/${productId}/restock`,
      { quantity, note },
    );
  }
}

function params(values: Record<string, string | number | undefined>): HttpParams {
  let result = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== '') {
      result = result.set(key, String(value));
    }
  }
  return result;
}
