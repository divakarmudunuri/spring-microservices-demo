import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, Pipe, PipeTransform, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Availability, Product, TimelineEntry } from '../core/models';

// Small presentational pieces shared by the storefront and the admin area.
// Styles live in src/styles.scss (one design system, small component budgets).

const AVAILABILITY_LABELS: Record<Availability, string> = {
  IN_STOCK: 'In stock',
  LOW_STOCK: 'Only a few left',
  OUT_OF_STOCK: 'Out of stock',
};

@Component({
  selector: 'app-availability',
  template: `@if (level()) {
    <span class="badge" [class]="'badge avail-' + level()!.toLowerCase()">{{ label() }}</span>
  }`,
})
export class AvailabilityBadge {
  readonly level = input<Availability | null>(null);
  protected readonly label = computed(() => (this.level() ? AVAILABILITY_LABELS[this.level()!] : ''));
}

/** Order, payment and shipment statuses: one colour family per meaning. */
@Component({
  selector: 'app-status',
  template: `<span class="badge" [class]="'badge status-' + tone()">{{ text() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<string>();
  protected readonly text = computed(() => humanize(this.status()));
  protected readonly tone = computed(() => statusTone(this.status()));
}

export function humanize(value: string): string {
  const lower = value.replaceAll('_', ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

export function statusTone(status: string): 'good' | 'bad' | 'warn' | 'info' | 'neutral' {
  if (/REJECTED|FAILED|CANCELLED|REFUNDED/.test(status)) return 'bad';
  if (/COMPLETED|DELIVERED|ACKNOWLEDGED|CAPTURED|CONFIRMED/.test(status)) return 'good';
  if (/INITIATED|PENDING/.test(status)) return 'warn';
  if (/FULFILLMENT|PICK|PACK|SHIP|TRANSIT|LABEL/.test(status)) return 'info';
  return 'neutral';
}

@Component({
  selector: 'app-product-card',
  imports: [RouterLink, CurrencyPipe, AvailabilityBadge],
  template: `
    <a class="product-card" [routerLink]="['/product', product().slug]">
      <img [src]="product().imageUrl" [alt]="product().name" loading="lazy" width="320" height="240" />
      <div class="product-card-body">
        <span class="muted small">{{ product().category.name }}</span>
        <strong>{{ product().name }}</strong>
        <div class="row-between">
          <span class="price">{{ product().price | currency: product().currency }}</span>
          <app-availability [level]="product().availability" />
        </div>
      </div>
    </a>
  `,
})
export class ProductCard {
  readonly product = input.required<Product>();
}

/** "Some of this page couldn't load": shown for degraded composed responses, never a blank page. */
@Component({
  selector: 'app-degraded',
  template: `@if (sections().length || message()) {
    <div class="notice warn" role="status">
      {{ message() || 'Some information is temporarily unavailable' }}@if (sections().length) {: {{ sectionNames() }}}.
    </div>
  }`,
})
export class DegradedNotice {
  readonly sections = input<string[]>([]);
  readonly message = input<string>('');
  /** Section ids come from the services ("newArrivals", "shipping"): show them as words. */
  protected readonly sectionNames = computed(() =>
    this.sections()
      .map((s) => s.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase())
      .join(', '),
  );
}

@Component({
  selector: 'app-error',
  template: `@if (message()) {
    <div class="notice error" role="alert">{{ message() }}</div>
  }`,
})
export class ErrorNotice {
  readonly message = input<string | null>(null);
}

@Component({
  selector: 'app-pager',
  template: `@if (totalPages() > 1) {
    <nav class="pager" aria-label="Pages">
      <button class="btn ghost" [disabled]="page() === 0" (click)="pageChange.emit(page() - 1)">Previous</button>
      <span class="muted">Page {{ page() + 1 }} of {{ totalPages() }}</span>
      <button class="btn ghost" [disabled]="page() + 1 >= totalPages()" (click)="pageChange.emit(page() + 1)">Next</button>
    </nav>
  }`,
})
export class Pager {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly pageChange = output<number>();
}

/** The tracking timeline (from order-tracking-service's DynamoDB read model), oldest first. */
@Component({
  selector: 'app-timeline',
  imports: [DatePipe, StatusBadge],
  template: `
    @if (entries().length) {
      <ol class="timeline">
        @for (entry of entries(); track $index) {
          <li>
            <div class="row-between">
              <app-status [status]="entry.status" />
              <time class="muted small">{{ entry.occurredAt | date: 'medium' }}</time>
            </div>
            <div class="muted small">{{ entry.source }}@if (detailText(entry)) { · {{ detailText(entry) }}}</div>
          </li>
        }
      </ol>
    } @else {
      <p class="muted">No tracking events yet.</p>
    }
  `,
})
export class Timeline {
  readonly entries = input<TimelineEntry[]>([]);

  protected detailText(entry: TimelineEntry): string {
    return entry.details
      ? Object.entries(entry.details)
          .map(([k, v]) => `${k}: ${v}`)
          .join(', ')
      : '';
  }
}

/**
 * UUIDs are long. The first block tells random ids (orders, payments) apart; `'tail'` shows the last block
 * instead, for the seeded users whose ids all start with zeros (…0000000000c1).
 */
@Pipe({ name: 'shortId' })
export class ShortIdPipe implements PipeTransform {
  transform(id: string | null | undefined, part: 'head' | 'tail' = 'head'): string {
    if (!id) return '';
    return part === 'tail' ? '…' + id.slice(-12) : id.slice(0, 8);
  }
}
