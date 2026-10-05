import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AccountApi } from '../core/apis';
import { OrderSummary, Page } from '../core/models';
import { problemMessage } from '../core/problem';
import { ErrorNotice, Pager, ShortIdPipe, StatusBadge } from '../shared/ui';

@Component({
  selector: 'app-orders',
  imports: [CurrencyPipe, DatePipe, RouterLink, StatusBadge, Pager, ErrorNotice, ShortIdPipe],
  template: `
    <h1>My orders</h1>
    <app-error [message]="error()" />
    @if (orders(); as o) {
      @if (o.content.length) {
        <div class="card table-wrap">
          <table>
            <thead>
              <tr><th>Order</th><th>Placed</th><th>Status</th><th class="num">Total</th></tr>
            </thead>
            <tbody>
              @for (order of o.content; track order.id) {
                <tr>
                  <td><a [routerLink]="['/orders', order.id]" class="mono">#{{ order.id | shortId }}</a></td>
                  <td>{{ order.createdAt | date: 'medium' }}</td>
                  <td><app-status [status]="order.status" /></td>
                  <td class="num">@if (order.totalAmount !== null) { {{ order.totalAmount | currency: order.currency }} }</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="o.page" [totalPages]="o.totalPages" (pageChange)="load($event)" />
      } @else {
        <p class="muted">No orders yet. <a routerLink="/">Go shopping</a>.</p>
      }
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class OrdersPage {
  private readonly account = inject(AccountApi);
  protected readonly orders = signal<Page<OrderSummary> | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.account.orders(page, 10).subscribe({
      next: (o) => this.orders.set(o),
      error: (e) => this.error.set(problemMessage(e)),
    });
  }
}
