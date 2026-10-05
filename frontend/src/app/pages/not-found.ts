import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <h1>Page not found</h1>
    <p class="muted">That page doesn't exist. <a routerLink="/">Back to the store</a>.</p>
  `,
})
export class NotFoundPage {}
