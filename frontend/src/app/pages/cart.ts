import { CurrencyPipe, NgTemplateOutlet } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable, switchMap } from 'rxjs';
import { CartStore } from '../core/cart-store';
import { LoginRedirect } from '../core/login-redirect';
import { Cart } from '../core/models';
import { problemMessage } from '../core/problem';
import { Session } from '../core/session';
import { AvailabilityBadge, DegradedNotice, ErrorNotice } from '../shared/ui';

@Component({
  selector: 'app-cart',
  imports: [CurrencyPipe, NgTemplateOutlet, FormsModule, RouterLink, AvailabilityBadge, DegradedNotice, ErrorNotice],
  template: `
    <h1>Your cart</h1>
    <app-error [message]="error()" />
    @if (store.cart(); as cart) {
      @if (cart.degraded) {
        <app-degraded message="Prices are temporarily unavailable. Your items are safe" />
      }
      @if (cart.lines.length) {
        <div class="card">
          @for (line of cart.lines; track line.productId) {
            <div class="cart-line">
              @if (line.imageUrl) {
                <img [src]="line.imageUrl" [alt]="line.name ?? ''" width="96" height="72" />
              } @else {
                <div class="img-placeholder"></div>
              }
              <div class="stack tight grow">
                @if (line.slug) {
                  <a [routerLink]="['/product', line.slug]"><strong>{{ line.name }}</strong></a>
                } @else {
                  <strong>{{ line.name ?? 'Product' }}</strong>
                }
                <div class="row small">
                  @if (line.unitPrice !== null) {
                    <span class="muted">{{ line.unitPrice | currency: cart.currency }} each</span>
                  }
                  <app-availability [level]="line.availability" />
                </div>
              </div>
              <select [ngModel]="line.quantity" (ngModelChange)="setQuantity(line.productId, $event)" [disabled]="busy()"
                      aria-label="Quantity">
                @for (n of quantities; track n) {
                  <option [ngValue]="n">{{ n }}</option>
                }
              </select>
              <span class="price line-total">
                @if (line.lineTotal !== null) { {{ line.lineTotal | currency: cart.currency }} }
              </span>
              <button class="link" [disabled]="busy()" (click)="remove(line.productId)">Remove</button>
            </div>
          }
          <div class="row-between total-row">
            <span>Subtotal</span>
            <strong class="price big">
              @if (cart.subtotal !== null) { {{ cart.subtotal | currency: cart.currency }} } @else { — }
            </strong>
          </div>
        </div>
        <div class="row-end">
          <a routerLink="/" class="btn ghost">Keep shopping</a>
          <button class="btn" (click)="checkout()">{{ session.isCustomer() ? 'Checkout' : 'Sign in to check out' }}</button>
        </div>
        @if (!session.isCustomer()) {
          <p class="muted small right">Your cart comes with you when you sign in.</p>
        }
      } @else {
        <ng-container *ngTemplateOutlet="empty" />
      }
    } @else {
      <ng-container *ngTemplateOutlet="empty" />
    }
    <ng-template #empty>
      <p class="muted">Your cart is empty.</p>
      <a routerLink="/" class="btn">Start shopping</a>
    </ng-template>
  `,
})
export class CartPage {
  protected readonly store = inject(CartStore);
  protected readonly session = inject(Session);
  private readonly login = inject(LoginRedirect);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly quantities = Array.from({ length: 11 }, (_, i) => i);

  constructor() {
    this.store
      .start()
      .pipe(switchMap(() => this.store.refresh()))
      .subscribe({ error: (e) => this.error.set(problemMessage(e)) });
  }

  protected setQuantity(productId: string, quantity: number): void {
    this.run(this.store.update(productId, quantity));
  }

  protected remove(productId: string): void {
    this.run(this.store.remove(productId));
  }

  protected checkout(): void {
    if (this.session.isCustomer()) {
      void this.router.navigate(['/checkout']);
    } else {
      this.login.customer('/checkout'); // the guest cart is merged right after sign-in
    }
  }

  private run(call: Observable<Cart>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => this.busy.set(false),
      error: (e) => {
        this.busy.set(false);
        this.error.set(problemMessage(e));
      },
    });
  }
}
