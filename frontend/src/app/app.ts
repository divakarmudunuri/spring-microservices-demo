import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Just an outlet: the storefront and the admin area each bring their own layout. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
