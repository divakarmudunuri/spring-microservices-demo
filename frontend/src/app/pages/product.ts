import { CurrencyPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { CatalogApi } from '../core/apis';
import { CartStore } from '../core/cart-store';
import { StorefrontProductPage } from '../core/models';
import { problemMessage } from '../core/problem';
import { AvailabilityBadge, DegradedNotice, ErrorNotice, ProductCard } from '../shared/ui';

/**
 * /product/:slug (not /products/…: nginx serves the product images under /products/).
 * Availability is a level fed by Kafka and can lag a moment; checkout is the authoritative stock check.
 */
@Component({
  selector: 'app-product',
  imports: [CurrencyPipe, FormsModule, RouterLink, AvailabilityBadge, ProductCard, DegradedNotice, ErrorNotice],
  template: `
    <app-error [message]="error()" />
    @if (page(); as pg) {
      @if (pg.degraded) {
        <app-degraded [sections]="pg.unavailableSections" />
      }
      <nav class="crumbs muted small">
        <a routerLink="/">Home</a> / <a [routerLink]="['/category', pg.product.category.slug]">{{ pg.product.category.name }}</a>
      </nav>
      <article class="product-detail">
        <img [src]="pg.product.imageUrl" [alt]="pg.product.name" width="640" height="480" />
        <div class="stack">
          <h1>{{ pg.product.name }}</h1>
          <div class="row">
            <span class="price big">{{ pg.product.price | currency: pg.product.currency }}</span>
            <app-availability [level]="pg.product.availability" />
          </div>
          <p>{{ pg.product.description }}</p>
          @if (pg.product.availability !== 'OUT_OF_STOCK') {
            <div class="row">
              <label class="inline">
                <span class="muted small">Qty</span>
                <select [(ngModel)]="quantity">
                  @for (n of quantities; track n) {
                    <option [ngValue]="n">{{ n }}</option>
                  }
                </select>
              </label>
              <button class="btn" [disabled]="adding()" (click)="addToCart(pg.product.id)">
                {{ adding() ? 'Adding…' : 'Add to cart' }}
              </button>
            </div>
          } @else {
            <p class="muted">Currently unavailable. Check back soon.</p>
          }
          @if (added()) {
            <div class="notice ok" role="status">Added to your cart. <a routerLink="/cart">View cart</a></div>
          }
          <app-error [message]="addError()" />
        </div>
      </article>
      @if (pg.related.length) {
        <h2>More in {{ pg.product.category.name }}</h2>
        <div class="grid">
          @for (p of pg.related; track p.id) {
            <app-product-card [product]="p" />
          }
        </div>
      }
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class ProductPage {
  private readonly catalog = inject(CatalogApi);
  private readonly cart = inject(CartStore);

  protected readonly page = signal<StorefrontProductPage | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly added = signal(false);
  protected readonly addError = signal<string | null>(null);
  protected readonly quantities = Array.from({ length: 10 }, (_, i) => i + 1);
  protected quantity = 1;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        tap(() => {
          this.page.set(null);
          this.error.set(null);
          this.added.set(false);
          this.addError.set(null);
          this.quantity = 1;
        }),
        switchMap((params) =>
          this.catalog.productPage(params.get('slug')!).pipe(
            catchError((e) => {
              this.error.set(problemMessage(e));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.page.set(page));
  }

  protected addToCart(productId: string): void {
    this.adding.set(true);
    this.added.set(false);
    this.addError.set(null);
    this.cart.add(productId, this.quantity).subscribe({
      next: () => {
        this.adding.set(false);
        this.added.set(true);
      },
      error: (e) => {
        this.adding.set(false);
        this.addError.set(problemMessage(e));
      },
    });
  }
}
