import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, switchMap, take, takeWhile, tap, timer } from 'rxjs';
import { AccountApi } from '../core/apis';
import { OrderDetails, OrderStatus } from '../core/models';
import { problemMessage } from '../core/problem';
import { OrderDetailsView } from '../shared/order-details-view';
import { ErrorNotice } from '../shared/ui';

/** Statuses that will still change on their own (fulfillment and shipping run asynchronously over Kafka). */
const IN_MOTION: OrderStatus[] = ['INITIATED', 'CONFIRMED', 'IN_FULFILLMENT', 'SHIPPED'];
/** The tracking event that closes each settled status. Tracking is fed by Kafka, so it can trail the order a moment. */
const CLOSING_EVENT: Partial<Record<OrderStatus, string>> = {
  REJECTED: 'ORDER_REJECTED',
  FAILED: 'ORDER_FAILED',
  CANCELLED: 'ORDER_CANCELLED',
  DELIVERED: 'ORDER_DELIVERED',
  COMPLETED: 'DELIVERY_ACKNOWLEDGED',
};
const POLL_MS = 3000;
const MAX_POLLS = 100; // ~5 minutes: enough for the demo's simulated delivery

/** Keep refreshing while the order is still moving, or while its tracking timeline hasn't caught up yet. */
export function stillChanging(d: OrderDetails): boolean {
  if (IN_MOTION.includes(d.status)) {
    return true;
  }
  const closing = CLOSING_EVENT[d.status];
  if (!closing || d.unavailableSections.includes('tracking')) {
    return false;
  }
  return !(d.tracking?.timeline ?? []).some((e) => e.status === closing);
}

@Component({
  selector: 'app-order-detail',
  imports: [RouterLink, OrderDetailsView, ErrorNotice],
  template: `
    <p><a routerLink="/orders" class="small">← My orders</a></p>
    @if (placed()) {
      <div class="notice ok" role="status">Thank you! Your order is confirmed and paid. Watch it move below.</div>
    }
    <app-error [message]="error()" />
    @if (details(); as d) {
      <app-order-details [details]="d">
        @if (d.status === 'DELIVERED') {
          <section class="card">
            <h2>Got it?</h2>
            <p class="muted small">Let us know your order arrived.</p>
            <button class="btn block" [disabled]="acknowledging()" (click)="acknowledge(d.id)">Confirm delivery</button>
          </section>
        }
        @if (d.status === 'COMPLETED') {
          <div class="notice ok">You confirmed this delivery. Thanks!</div>
        }
      </app-order-details>
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class OrderDetailPage {
  private readonly account = inject(AccountApi);
  protected readonly details = signal<OrderDetails | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly acknowledging = signal(false);
  protected readonly placed = signal(false);
  private readonly watch = new Subject<string>();

  constructor() {
    const route = inject(ActivatedRoute);
    this.placed.set(route.snapshot.queryParamMap.has('placed'));
    // each (re)start of watching an order cancels the previous one
    this.watch
      .pipe(
        switchMap((id) =>
          timer(0, POLL_MS).pipe(
            take(MAX_POLLS),
            switchMap(() =>
              this.account.orderDetails(id).pipe(
                catchError((e) => {
                  this.error.set(problemMessage(e));
                  return EMPTY;
                }),
              ),
            ),
            tap((d) => {
              this.error.set(null);
              this.details.set(d);
            }),
            takeWhile(stillChanging),
          ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe();
    route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.details.set(null);
      this.error.set(null);
      this.watch.next(params.get('id')!);
    });
  }

  protected acknowledge(id: string): void {
    this.acknowledging.set(true);
    this.account.acknowledgeDelivery(id).subscribe({
      next: () => {
        this.acknowledging.set(false);
        this.watch.next(id); // COMPLETED now; keep refreshing until the timeline shows the acknowledgement
      },
      error: (e) => {
        this.acknowledging.set(false);
        this.error.set(problemMessage(e));
      },
    });
  }
}
