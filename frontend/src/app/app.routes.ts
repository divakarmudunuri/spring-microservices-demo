import { Routes } from '@angular/router';
import { adminGuard, customerGuard } from './core/guards';
import { CartPage } from './pages/cart';
import { CatalogPage } from './pages/catalog';
import { HomePage } from './pages/home';
import { NotFoundPage } from './pages/not-found';
import { ProductPage } from './pages/product';
import { StoreLayout } from './pages/store-layout';

export const routes: Routes = [
  {
    path: 'admin',
    canActivate: [adminGuard],
    // the admin screens are a separate bundle: shoppers never download them
    loadChildren: () => import('./admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
  {
    path: '',
    component: StoreLayout,
    children: [
      { path: '', component: HomePage, title: 'SMD store' },
      { path: 'category/:slug', component: CatalogPage, title: 'Shop · SMD store' },
      { path: 'search', component: CatalogPage, title: 'Search · SMD store' },
      // /product, not /products: nginx serves the product images under /products/
      { path: 'product/:slug', component: ProductPage, title: 'Product · SMD store' },
      { path: 'cart', component: CartPage, title: 'Cart · SMD store' },
      {
        path: 'checkout',
        canActivate: [customerGuard],
        loadComponent: () => import('./pages/checkout').then((m) => m.CheckoutPage),
        title: 'Checkout · SMD store',
      },
      {
        path: 'orders',
        canActivate: [customerGuard],
        loadComponent: () => import('./pages/orders').then((m) => m.OrdersPage),
        title: 'My orders · SMD store',
      },
      {
        path: 'orders/:id',
        canActivate: [customerGuard],
        loadComponent: () => import('./pages/order-detail').then((m) => m.OrderDetailPage),
        title: 'Order · SMD store',
      },
      { path: '**', component: NotFoundPage, title: 'Not found · SMD store' },
    ],
  },
];
