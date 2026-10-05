import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, of, switchMap } from 'rxjs';
import { LoginRedirect } from '../core/login-redirect';
import { Inventory, OrderDetails, OrderSummary, Page, PaymentsReport, Shipment } from '../core/models';
import { problemMessage } from '../core/problem';
import { Session } from '../core/session';
import { OrderDetailsView } from '../shared/order-details-view';
import { DegradedNotice, ErrorNotice, Pager, ShortIdPipe, StatusBadge, humanize } from '../shared/ui';
import { AdminApi } from './admin-api';

// The admin area (/admin/**). nginx serves these pages only with an Okta admin session, and every
// /api/admin call is checked again by the gateway (ADMIN role) and by the owning service.

const ORDER_STATUSES = ['INITIATED', 'CONFIRMED', 'REJECTED', 'FAILED', 'IN_FULFILLMENT', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED'];
const PAYMENT_STATUSES = ['CAPTURED', 'REFUNDED'];
const SHIPMENT_STATUSES = ['LABEL_CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'];

@Component({
  selector: 'app-admin-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="site-header admin">
      <div class="container header-row">
        <a routerLink="/admin" class="brand">SMD<span>admin</span></a>
        <nav class="admin-nav">
          <a routerLink="/admin/orders" routerLinkActive="active">Orders</a>
          <a routerLink="/admin/payments" routerLinkActive="active">Payments</a>
          <a routerLink="/admin/shipments" routerLinkActive="active">Shipments</a>
          <a routerLink="/admin/inventory" routerLinkActive="active">Inventory</a>
        </nav>
        <nav class="account">
          <span class="muted small hide-sm">{{ session.admin()?.email }}</span>
          <a href="/" class="small">Storefront</a>
          <button class="link" (click)="login.signOutAdmin()">Sign out</button>
        </nav>
      </div>
    </header>
    <main class="container page">
      <router-outlet />
    </main>
  `,
})
export class AdminLayout {
  protected readonly session = inject(Session);
  protected readonly login = inject(LoginRedirect);
}

@Component({
  selector: 'app-admin-orders',
  imports: [FormsModule, CurrencyPipe, DatePipe, RouterLink, StatusBadge, Pager, ErrorNotice, ShortIdPipe],
  template: `
    <h1>Orders</h1>
    <form class="filters" (ngSubmit)="load(0)">
      <label class="inline"><span class="muted small">Status</span>
        <select name="status" [(ngModel)]="status" (ngModelChange)="load(0)">
          <option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
        </select>
      </label>
      <label class="inline"><span class="muted small">Customer id</span>
        <input name="userId" [(ngModel)]="userId" placeholder="UUID" class="mono" />
      </label>
      <button class="btn ghost small">Apply</button>
    </form>
    <app-error [message]="error()" />
    @if (orders(); as o) {
      <p class="muted small">{{ o.totalElements }} orders</p>
      <div class="card table-wrap">
        <table>
          <thead><tr><th>Order</th><th>Placed</th><th>Customer</th><th>Status</th><th>Reason</th><th class="num">Total</th></tr></thead>
          <tbody>
            @for (order of o.content; track order.id) {
              <tr>
                <td><a [routerLink]="['/admin/orders', order.id]" class="mono">#{{ order.id | shortId }}</a></td>
                <td>{{ order.createdAt | date: 'short' }}</td>
                <td class="mono small" [title]="order.userId">{{ order.userId | shortId: 'tail' }}</td>
                <td><app-status [status]="order.status" /></td>
                <td class="small muted">{{ order.rejectionReason ? label(order.rejectionReason) : '' }}</td>
                <td class="num">@if (order.totalAmount !== null) { {{ order.totalAmount | currency: order.currency }} }</td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="muted">No orders match.</td></tr>
            }
          </tbody>
        </table>
      </div>
      <app-pager [page]="o.page" [totalPages]="o.totalPages" (pageChange)="load($event)" />
    }
  `,
})
export class AdminOrdersPage {
  private readonly api = inject(AdminApi);
  protected readonly statuses = ORDER_STATUSES;
  protected readonly label = humanize;
  protected readonly orders = signal<Page<OrderSummary> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected status = '';
  protected userId = '';

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.error.set(null);
    this.api.orders({ status: this.status, userId: this.userId.trim(), page, size: 20 }).subscribe({
      next: (o) => this.orders.set(o),
      error: (e) => this.error.set(problemMessage(e)),
    });
  }
}

@Component({
  selector: 'app-admin-order-detail',
  imports: [RouterLink, OrderDetailsView, ErrorNotice],
  template: `
    <p><a routerLink="/admin/orders" class="small">← All orders</a></p>
    <app-error [message]="error()" />
    @if (details(); as d) {
      <app-order-details [details]="d" [showCustomer]="true" />
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class AdminOrderDetailPage {
  protected readonly details = signal<OrderDetails | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const api = inject(AdminApi);
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((p) =>
          api.orderDetails(p.get('id')!).pipe(
            catchError((e) => {
              this.error.set(problemMessage(e));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((d) => this.details.set(d));
  }
}

@Component({
  selector: 'app-admin-payments',
  imports: [FormsModule, CurrencyPipe, DatePipe, RouterLink, StatusBadge, Pager, ErrorNotice, ShortIdPipe],
  template: `
    <h1>Payments</h1>
    <app-error [message]="error()" />
    @if (report(); as r) {
      <div class="stats">
        @for (t of r.totalsByStatus; track t.status) {
          <div class="card stat">
            <app-status [status]="t.status" />
            <!-- the demo has one currency; totals by status come without one -->
            <strong class="price big">{{ t.amount | currency: 'USD' }}</strong>
            <span class="muted small">{{ t.count }} payment{{ t.count === 1 ? '' : 's' }}</span>
          </div>
        }
      </div>
      <form class="filters">
        <label class="inline"><span class="muted small">Status</span>
          <select name="status" [(ngModel)]="status" (ngModelChange)="load(0)">
            <option value="">All</option>
            @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
      </form>
      <div class="card table-wrap">
        <table>
          <thead><tr><th>Payment</th><th>Order</th><th>Customer</th><th>Taken</th><th>Status</th><th class="num">Amount</th></tr></thead>
          <tbody>
            @for (p of r.payments.content; track p.id) {
              <tr>
                <td class="mono small">{{ p.id | shortId }}</td>
                <td><a [routerLink]="['/admin/orders', p.orderId]" class="mono">#{{ p.orderId | shortId }}</a></td>
                <td class="mono small" [title]="p.userId">{{ p.userId | shortId: 'tail' }}</td>
                <td>{{ p.createdAt | date: 'short' }}</td>
                <td><app-status [status]="p.status" /></td>
                <td class="num">{{ p.amount | currency: p.currency }}</td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="muted">No payments match.</td></tr>
            }
          </tbody>
        </table>
      </div>
      <app-pager [page]="r.payments.page" [totalPages]="r.payments.totalPages" (pageChange)="load($event)" />
    }
  `,
})
export class AdminPaymentsPage {
  private readonly api = inject(AdminApi);
  protected readonly statuses = PAYMENT_STATUSES;
  protected readonly report = signal<PaymentsReport | null>(null);
  protected readonly error = signal<string | null>(null);
  protected status = '';

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.api.payments({ status: this.status, page, size: 20 }).subscribe({
      next: (r) => this.report.set(r),
      error: (e) => this.error.set(problemMessage(e)),
    });
  }
}

@Component({
  selector: 'app-admin-shipments',
  imports: [FormsModule, DatePipe, RouterLink, StatusBadge, Pager, ErrorNotice, ShortIdPipe],
  template: `
    <h1>Shipments</h1>
    <form class="filters">
      <label class="inline"><span class="muted small">Status</span>
        <select name="status" [(ngModel)]="status" (ngModelChange)="load(0)">
          <option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
        </select>
      </label>
    </form>
    <app-error [message]="error()" />
    @if (shipments(); as page) {
      <div class="card table-wrap">
        <table>
          <thead><tr><th>Tracking #</th><th>Order</th><th>Carrier</th><th>Status</th><th>ETA / delivered</th><th>Ship to</th></tr></thead>
          <tbody>
            @for (s of page.content; track s.id) {
              <tr>
                <td class="mono small">{{ s.trackingNumber }}</td>
                <td><a [routerLink]="['/admin/orders', s.orderId]" class="mono">#{{ s.orderId | shortId }}</a></td>
                <td>{{ s.carrier }}</td>
                <td><app-status [status]="s.status" /></td>
                <td class="small">
                  @if (s.deliveredAt) { {{ s.deliveredAt | date: 'short' }} } @else { {{ s.estimatedDelivery | date: 'mediumDate' }} }
                </td>
                <td class="small">{{ s.shippingAddress?.city }}@if (s.shippingAddress?.country) {, {{ s.shippingAddress?.country }}}</td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="muted">No shipments match.</td></tr>
            }
          </tbody>
        </table>
      </div>
      <app-pager [page]="page.page" [totalPages]="page.totalPages" (pageChange)="load($event)" />
    }
  `,
})
export class AdminShipmentsPage {
  private readonly api = inject(AdminApi);
  protected readonly statuses = SHIPMENT_STATUSES;
  protected readonly label = humanize;
  protected readonly shipments = signal<Page<Shipment> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected status = '';

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.api.shipments({ status: this.status, page, size: 20 }).subscribe({
      next: (s) => this.shipments.set(s),
      error: (e) => this.error.set(problemMessage(e)),
    });
  }
}

/** Exact stock (order-service owns inventory); the storefront only ever sees levels. */
@Component({
  selector: 'app-admin-inventory',
  imports: [FormsModule, DatePipe, DegradedNotice, ErrorNotice, ShortIdPipe],
  template: `
    <h1>Inventory</h1>
    <app-error [message]="error()" />
    @if (inventory(); as inv) {
      @if (!inv.namesAvailable) {
        <app-degraded message="Product names are temporarily unavailable" />
      }
      @if (message()) {
        <div class="notice ok" role="status">{{ message() }}</div>
      }
      <div class="card table-wrap">
        <table>
          <thead><tr><th>Product</th><th class="num">On hand</th><th>Updated</th><th>Restock</th></tr></thead>
          <tbody>
            @for (item of inv.items; track item.productId) {
              <tr [class.low]="item.quantityOnHand <= 5">
                <td>{{ item.name ?? (item.productId | shortId) }}</td>
                <td class="num"><strong>{{ item.quantityOnHand }}</strong></td>
                <td class="small muted">{{ item.updatedAt | date: 'short' }}</td>
                <td>
                  <form class="row tight" (ngSubmit)="restock(item.productId)">
                    <input type="number" [name]="'qty-' + item.productId" [(ngModel)]="quantities[item.productId]"
                           min="1" max="10000" placeholder="Qty" class="narrow" aria-label="Quantity to add" />
                    <input [name]="'note-' + item.productId" [(ngModel)]="notes[item.productId]" maxlength="500"
                           placeholder="Note (optional)" aria-label="Note" />
                    <button class="btn small" [disabled]="busy() === item.productId || !(quantities[item.productId] > 0)">Add</button>
                  </form>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class AdminInventoryPage {
  private readonly api = inject(AdminApi);
  protected readonly inventory = signal<Inventory | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected quantities: Record<string, number> = {};
  protected notes: Record<string, string> = {};

  constructor() {
    this.load();
  }

  protected restock(productId: string): void {
    const quantity = this.quantities[productId];
    this.busy.set(productId);
    this.error.set(null);
    this.message.set(null);
    this.api.restock(productId, quantity, this.notes[productId] ?? '').subscribe({
      next: (r) => {
        this.busy.set(null);
        delete this.quantities[productId];
        delete this.notes[productId];
        const name = this.inventory()?.items.find((i) => i.productId === productId)?.name ?? 'Product';
        this.message.set(`${name}: now ${r.quantityOnHand} on hand. The storefront picks this up via Kafka in a moment.`);
        this.load();
      },
      error: (e) => {
        this.busy.set(null);
        this.error.set(problemMessage(e));
      },
    });
  }

  private load(): void {
    this.api.inventory().subscribe({
      next: (i) => this.inventory.set(i),
      error: (e) => this.error.set(problemMessage(e)),
    });
  }
}
