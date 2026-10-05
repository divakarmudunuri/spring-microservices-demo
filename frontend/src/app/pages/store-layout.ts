import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { CatalogApi } from '../core/apis';
import { CartStore } from '../core/cart-store';
import { LoginRedirect } from '../core/login-redirect';
import { Category } from '../core/models';
import { Session } from '../core/session';

/** The storefront's frame: header (search, categories, cart, account) around every shop page. */
@Component({
  selector: 'app-store-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  template: `
    <header class="site-header">
      <div class="container header-row">
        <a routerLink="/" class="brand">SMD<span>store</span></a>
        <form class="search" role="search" (ngSubmit)="search()">
          <input type="search" name="q" [(ngModel)]="query" placeholder="Search products" aria-label="Search products" />
        </form>
        <nav class="account">
          @if (session.isCustomer()) {
            <a routerLink="/orders" routerLinkActive="active">My orders</a>
            <span class="muted small hide-sm">{{ session.customer()?.fullName }}</span>
            <button class="link" (click)="login.signOutCustomer()">Sign out</button>
          } @else {
            <button class="btn small" (click)="login.customer()">Sign in with Google</button>
          }
          <a routerLink="/cart" class="cart-link" routerLinkActive="active">
            Cart @if (cart.itemCount()) { <span class="count">{{ cart.itemCount() }}</span> }
          </a>
        </nav>
      </div>
      <nav class="container categories" aria-label="Categories">
        <a routerLink="/" [routerLinkActiveOptions]="{ exact: true }" routerLinkActive="active">Home</a>
        @for (c of categories(); track c.slug) {
          <a [routerLink]="['/category', c.slug]" routerLinkActive="active">{{ c.name }}</a>
        }
      </nav>
    </header>
    <main class="container page">
      <router-outlet />
    </main>
    <footer class="site-footer">
      <div class="container muted small">spring-microservices-demo · a learning project, not a real shop</div>
    </footer>
  `,
})
export class StoreLayout {
  protected readonly session = inject(Session);
  protected readonly cart = inject(CartStore);
  protected readonly login = inject(LoginRedirect);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogApi);

  protected readonly categories = signal<Category[]>([]);
  protected query = '';

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.catalog
      .categories()
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({ next: (c) => this.categories.set(c), error: () => this.categories.set([]) });

    this.cart.start().pipe(takeUntilDestroyed(destroyRef)).subscribe();
  }

  protected search(): void {
    const q = this.query.trim();
    if (q) {
      void this.router.navigate(['/search'], { queryParams: { q } });
    }
  }
}
