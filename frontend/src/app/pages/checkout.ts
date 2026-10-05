import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { AccountApi } from '../core/apis';
import { CartStore } from '../core/cart-store';
import { Address, Wallet } from '../core/models';
import { problemMessage, problemOf } from '../core/problem';
import { Session } from '../core/session';
import { DegradedNotice, ErrorNotice } from '../shared/ui';

const EMPTY_ADDRESS: Address = { fullName: '', line1: '', line2: '', city: '', state: '', postalCode: '', country: 'US', phone: '' };

/**
 * Address + wallet + "Place order". Placing the order *is* the payment: order-service checks stock,
 * decrements it and debits the wallet in one database transaction, so the answer is final right away.
 */
@Component({
  selector: 'app-checkout',
  imports: [CurrencyPipe, FormsModule, RouterLink, DegradedNotice, ErrorNotice],
  template: `
    <h1>Checkout</h1>
    <div class="two-col">
      <div class="stack">
        <section class="card">
          <div class="row-between">
            <h2>Shipping address</h2>
            @if (address() && !editingAddress()) {
              <button class="link" (click)="editAddress()">Change</button>
            }
          </div>
          @if (address() && !editingAddress()) {
            <address>
              {{ address()!.fullName }}<br />
              {{ address()!.line1 }}@if (address()!.line2) {, {{ address()!.line2 }}}<br />
              {{ address()!.city }}, {{ address()!.state }} {{ address()!.postalCode }}, {{ address()!.country }}
            </address>
          } @else {
            @if (!address()) {
              <p class="muted small">Add an address so we know where to send your order.</p>
            }
            <form class="form-grid" (ngSubmit)="saveAddress()" #f="ngForm">
              <label class="span-2">Full name <input name="fullName" [(ngModel)]="draft.fullName" required maxlength="200" /></label>
              <label class="span-2">Address line 1 <input name="line1" [(ngModel)]="draft.line1" required maxlength="200" /></label>
              <label class="span-2">Address line 2 <input name="line2" [(ngModel)]="draft.line2" maxlength="200" /></label>
              <label>City <input name="city" [(ngModel)]="draft.city" required maxlength="100" /></label>
              <label>State <input name="state" [(ngModel)]="draft.state" required maxlength="100" /></label>
              <label>Postal code <input name="postalCode" [(ngModel)]="draft.postalCode" required maxlength="20" /></label>
              <label>Country <input name="country" [(ngModel)]="draft.country" required pattern="[A-Za-z]{2}" maxlength="2" /></label>
              <label class="span-2">Phone <input name="phone" [(ngModel)]="draft.phone" maxlength="30" /></label>
              <div class="span-2 row-end">
                @if (address()) {
                  <button type="button" class="btn ghost" (click)="editingAddress.set(false)">Cancel</button>
                }
                <button class="btn" [disabled]="f.invalid || savingAddress()">Save address</button>
              </div>
            </form>
            <app-error [message]="addressError()" />
          }
        </section>

        <section class="card">
          <h2>Wallet</h2>
          @if (wallet(); as w) {
            <p>Balance: <strong class="price">{{ w.balance | currency: w.currency }}</strong></p>
            @if (shortfall() > 0) {
              <div class="notice warn">Your balance is {{ shortfall() | currency: w.currency }} short of this order. Top up below.</div>
            }
            <form class="row" (ngSubmit)="topUp()">
              <label class="inline">
                <span class="muted small">Top up</span>
                <input type="number" name="amount" [(ngModel)]="topUpAmount" min="0.01" max="1000" step="0.01" class="narrow" />
              </label>
              <button class="btn ghost" [disabled]="toppingUp() || !(topUpAmount > 0 && topUpAmount <= 1000)">Add funds</button>
            </form>
            <p class="muted small">Demo money, up to {{ 1000 | currency: w.currency }} per top-up.</p>
          }
          <app-error [message]="walletError()" />
        </section>
      </div>

      <aside class="card summary">
        <h2>Order summary</h2>
        @if (cart.cart(); as c) {
          @if (c.degraded) {
            <app-degraded message="Prices are temporarily unavailable" />
          }
          @for (line of c.lines; track line.productId) {
            <div class="row-between small">
              <span>{{ line.quantity }} × {{ line.name ?? 'Product' }}</span>
              <span>@if (line.lineTotal !== null) { {{ line.lineTotal | currency: c.currency }} }</span>
            </div>
          }
          <div class="row-between total-row">
            <span>Total</span>
            <strong class="price big">@if (c.subtotal !== null) { {{ c.subtotal | currency: c.currency }} } @else { — }</strong>
          </div>
        }
        @if (cartEmpty()) {
          <p class="muted">Your cart is empty. <a routerLink="/">Find something you like</a>.</p>
        }
        <button class="btn block" [disabled]="!canPlace()" (click)="placeOrder()">
          {{ placing() ? 'Placing order…' : 'Place order and pay' }}
        </button>
        <app-error [message]="orderError()" />
        @if (failedOrderId()) {
          <p class="small"><a [routerLink]="['/orders', failedOrderId()]">See this attempt in your orders</a></p>
        }
      </aside>
    </div>
  `,
})
export class CheckoutPage {
  protected readonly cart = inject(CartStore);
  private readonly session = inject(Session);
  private readonly account = inject(AccountApi);
  private readonly router = inject(Router);

  protected readonly address = computed(() => this.session.customer()?.defaultAddress ?? null);
  protected readonly editingAddress = signal(false);
  protected readonly savingAddress = signal(false);
  protected readonly addressError = signal<string | null>(null);
  protected draft: Address = { ...EMPTY_ADDRESS };

  protected readonly wallet = signal<Wallet | null>(null);
  protected readonly walletError = signal<string | null>(null);
  protected readonly toppingUp = signal(false);
  protected topUpAmount = 50;

  protected readonly placing = signal(false);
  protected readonly orderError = signal<string | null>(null);
  protected readonly failedOrderId = signal<string | null>(null);

  protected readonly cartEmpty = computed(() => !this.cart.cart()?.lines.length);
  protected readonly shortfall = computed(() => {
    const total = this.cart.cart()?.subtotal ?? 0;
    const balance = this.wallet()?.balance ?? 0;
    return Math.max(0, Number((total - balance).toFixed(2)));
  });
  protected readonly canPlace = computed(
    () => !this.placing() && !this.cartEmpty() && !!this.address() && !this.editingAddress(),
  );

  constructor() {
    this.cart
      .start()
      .pipe(switchMap(() => this.cart.refresh()))
      .subscribe({ error: (e) => this.orderError.set(problemMessage(e)) });
    this.account.wallet().subscribe({
      next: (w) => this.wallet.set(w),
      error: (e) => this.walletError.set(problemMessage(e)),
    });
  }

  protected editAddress(): void {
    this.draft = { ...EMPTY_ADDRESS, ...this.address() };
    this.editingAddress.set(true);
  }

  protected saveAddress(): void {
    this.savingAddress.set(true);
    this.addressError.set(null);
    const body: Address = { ...this.draft, country: this.draft.country.toUpperCase() };
    this.account.saveAddress(body).subscribe({
      next: (user) => {
        this.session.setCustomer(user);
        this.savingAddress.set(false);
        this.editingAddress.set(false);
      },
      error: (e) => {
        this.savingAddress.set(false);
        this.addressError.set(problemMessage(e));
      },
    });
  }

  protected topUp(): void {
    this.toppingUp.set(true);
    this.walletError.set(null);
    // a fresh key per click: a network retry of *this* request counts once, a second click is a second top-up
    this.account.topUp(this.topUpAmount, crypto.randomUUID()).subscribe({
      next: (w) => {
        this.wallet.set(w);
        this.toppingUp.set(false);
      },
      error: (e) => {
        this.toppingUp.set(false);
        this.walletError.set(problemMessage(e));
      },
    });
  }

  protected placeOrder(): void {
    this.placing.set(true);
    this.orderError.set(null);
    this.failedOrderId.set(null);
    this.account.checkout().subscribe({
      next: (order) => {
        // cart-service empties the cart when it sees ORDER_CONFIRMED on Kafka; don't wait for it here
        this.cart.forgetLocally();
        void this.router.navigate(['/orders', order.id], { queryParams: { placed: 1 } });
      },
      error: (e) => {
        this.placing.set(false);
        this.orderError.set(problemMessage(e));
        // rejected orders still exist (and are tracked); order-service returns their id
        this.failedOrderId.set(problemOf(e)?.orderId ?? null);
        this.account.wallet().subscribe({ next: (w) => this.wallet.set(w), error: () => undefined });
      },
    });
  }
}
