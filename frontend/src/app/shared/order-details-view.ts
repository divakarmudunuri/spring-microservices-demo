import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { OrderDetails } from '../core/models';
import { DegradedNotice, ShortIdPipe, StatusBadge, Timeline, humanize } from './ui';

/**
 * One order, as returned by the details aggregator (order-service fans out to user, product, shipping
 * and tracking in parallel). Sections that didn't answer in time are listed, the rest still shows.
 */
@Component({
  selector: 'app-order-details',
  imports: [CurrencyPipe, DatePipe, DegradedNotice, StatusBadge, Timeline, ShortIdPipe],
  template: `
    @let d = details();
    <div class="row-between wrap">
      <h1>Order <span class="mono">#{{ d.id | shortId }}</span></h1>
      <app-status [status]="d.status" />
    </div>
    <p class="muted small">Placed {{ d.createdAt | date: 'medium' }} · <span class="mono">{{ d.id }}</span></p>
    @if (d.degraded) {
      <app-degraded [sections]="d.unavailableSections" />
    }
    <div class="two-col">
      <div class="stack">
        <section class="card">
          <h2>Items</h2>
          @for (item of d.items; track item.productId) {
            <div class="row-between">
              <span>{{ item.quantity }} × {{ item.name ?? 'Product ' + (item.productId | shortId) }}</span>
              <span>@if (item.unitPrice !== null) { {{ item.unitPrice * item.quantity | currency: d.currency }} }</span>
            </div>
          }
          @if (d.totalAmount !== null) {
            <div class="row-between total-row"><span>Total</span><strong class="price">{{ d.totalAmount | currency: d.currency }}</strong></div>
          }
        </section>
        <section class="card">
          <h2>Tracking</h2>
          <app-timeline [entries]="d.tracking?.timeline ?? []" />
        </section>
      </div>
      <div class="stack">
        @if (showCustomer() && d.customer) {
          <section class="card">
            <h2>Customer</h2>
            <p>{{ d.customer.fullName }}<br /><span class="muted">{{ d.customer.email }}</span></p>
          </section>
        }
        <section class="card">
          <h2>Delivery</h2>
          @if (d.shipment; as s) {
            <p>
              <app-status [status]="s.status" /><br />
              {{ s.carrier }} · <span class="mono">{{ s.trackingNumber }}</span><br />
              @if (s.deliveredAt) {
                Delivered {{ s.deliveredAt | date: 'medium' }}
              } @else if (s.estimatedDelivery) {
                Estimated {{ s.estimatedDelivery | date: 'mediumDate' }}
              }
            </p>
          } @else {
            <p class="muted">{{ noShipmentText() }}</p>
          }
          @if (d.shippingAddress; as a) {
            <address class="small">
              {{ a.fullName }}<br />{{ a.line1 }}@if (a.line2) {, {{ a.line2 }}}<br />
              {{ a.city }}, {{ a.state }} {{ a.postalCode }}, {{ a.country }}
            </address>
          }
        </section>
        <ng-content />
      </div>
    </div>
  `,
})
export class OrderDetailsView {
  readonly details = input.required<OrderDetails>();
  readonly showCustomer = input(false);

  protected noShipmentText(): string {
    const status = this.details().status;
    if (['REJECTED', 'FAILED', 'CANCELLED'].includes(status)) {
      return `No shipment: the order was ${humanize(status).toLowerCase()}.`;
    }
    return 'Not shipped yet.';
  }
}
