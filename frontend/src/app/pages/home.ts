import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CatalogApi } from '../core/apis';
import { StorefrontHome } from '../core/models';
import { problemMessage } from '../core/problem';
import { DegradedNotice, ErrorNotice, ProductCard } from '../shared/ui';

/** One call to the BFF, which fetches categories, featured and new arrivals in parallel. */
@Component({
  selector: 'app-home',
  imports: [RouterLink, ProductCard, DegradedNotice, ErrorNotice],
  template: `
    <section class="hero">
      <h1>Everyday things, well made.</h1>
      <p class="muted">Browse freely. Add to your cart without an account; sign in with Google when you're ready to check out.</p>
    </section>

    <app-error [message]="error()" />
    @if (home(); as h) {
      @if (h.degraded) {
        <app-degraded [sections]="h.unavailableSections" />
      }
      @if (h.categories.length) {
        <div class="chips">
          @for (c of h.categories; track c.slug) {
            <a class="chip" [routerLink]="['/category', c.slug]">{{ c.name }}</a>
          }
        </div>
      }
      @if (h.featured.length) {
        <h2>Featured</h2>
        <div class="grid">
          @for (p of h.featured; track p.id) {
            <app-product-card [product]="p" />
          }
        </div>
      }
      @if (h.newArrivals.length) {
        <h2>New arrivals</h2>
        <div class="grid">
          @for (p of h.newArrivals; track p.id) {
            <app-product-card [product]="p" />
          }
        </div>
      }
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class HomePage {
  protected readonly home = signal<StorefrontHome | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    inject(CatalogApi)
      .home()
      .pipe(takeUntilDestroyed())
      .subscribe({ next: (h) => this.home.set(h), error: (e) => this.error.set(problemMessage(e)) });
  }
}
