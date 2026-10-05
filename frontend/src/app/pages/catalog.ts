import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, combineLatest, of, switchMap, tap } from 'rxjs';
import { CatalogApi } from '../core/apis';
import { Category, Page, Product } from '../core/models';
import { problemMessage } from '../core/problem';
import { ErrorNotice, Pager, ProductCard } from '../shared/ui';

/** A category (/category/:slug) or a search (/search?q=), as a paged product grid. */
@Component({
  selector: 'app-catalog',
  imports: [FormsModule, ProductCard, Pager, ErrorNotice],
  template: `
    <div class="row-between wrap">
      <h1>{{ title() }}</h1>
      <label class="inline">
        <span class="muted small">Sort</span>
        <select [ngModel]="sort()" (ngModelChange)="changeSort($event)">
          <option value="name">Name</option>
          <option value="price-asc">Price: low to high</option>
          <option value="price-desc">Price: high to low</option>
          <option value="newest">Newest</option>
        </select>
      </label>
    </div>
    <app-error [message]="error()" />
    @if (results(); as r) {
      @if (r.content.length) {
        <p class="muted small">{{ r.totalElements }} product{{ r.totalElements === 1 ? '' : 's' }}</p>
        <div class="grid">
          @for (p of r.content; track p.id) {
            <app-product-card [product]="p" />
          }
        </div>
        <app-pager [page]="r.page" [totalPages]="r.totalPages" (pageChange)="goToPage($event)" />
      } @else {
        <p class="muted">No products found.</p>
      }
    } @else if (!error()) {
      <p class="muted">Loading…</p>
    }
  `,
})
export class CatalogPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogApi);

  protected readonly results = signal<Page<Product> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly sort = signal('name');
  private readonly category = signal<string | null>(null);
  private readonly query = signal<string | null>(null);
  private readonly categories = signal<Category[]>([]);

  protected readonly title = computed(() => {
    const q = this.query();
    if (q) return `Results for “${q}”`;
    const slug = this.category();
    return this.categories().find((c) => c.slug === slug)?.name ?? 'Products';
  });

  constructor() {
    this.catalog
      .categories()
      .pipe(takeUntilDestroyed())
      .subscribe({ next: (c) => this.categories.set(c), error: () => undefined });

    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(
        tap(([params, query]) => {
          this.category.set(params.get('slug'));
          this.query.set(query.get('q'));
          this.sort.set(query.get('sort') ?? 'name');
          this.results.set(null);
          this.error.set(null);
        }),
        switchMap(([params, query]) =>
          this.catalog
            .products({
              category: params.get('slug') ?? undefined,
              q: query.get('q') ?? undefined,
              sort: query.get('sort') ?? 'name',
              page: Number(query.get('page') ?? 0),
              size: 12,
            })
            .pipe(
              catchError((e) => {
                this.error.set(problemMessage(e));
                return of(null);
              }),
            ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.results.set(page));
  }

  protected changeSort(sort: string): void {
    void this.router.navigate([], { queryParams: { sort, page: null }, queryParamsHandling: 'merge' });
  }

  protected goToPage(page: number): void {
    void this.router.navigate([], { queryParams: { page }, queryParamsHandling: 'merge' });
  }
}
