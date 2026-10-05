import { Injectable } from '@angular/core';

const KEY = 'smd.guestCartId';

/**
 * The guest cart's id. It is a bearer secret (whoever has it can read the cart), so it lives in
 * localStorage and travels only in the X-Cart-Id header on /api/cart calls, never as a cookie
 * (a cookie would be sent automatically, to every path). See cart-service's CartController.
 */
@Injectable({ providedIn: 'root' })
export class GuestCartId {
  get(): string | null {
    try {
      return localStorage.getItem(KEY);
    } catch {
      return null; // storage blocked (private mode, sandboxed preview): behave as "no guest cart"
    }
  }

  set(id: string): void {
    try {
      localStorage.setItem(KEY, id);
    } catch {
      // without storage the guest cart simply doesn't survive a reload
    }
  }

  clear(): void {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // nothing stored, nothing to clear
    }
  }
}
