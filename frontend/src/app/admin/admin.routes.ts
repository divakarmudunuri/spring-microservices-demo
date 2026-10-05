import { Routes } from '@angular/router';
import {
  AdminInventoryPage,
  AdminLayout,
  AdminOrderDetailPage,
  AdminOrdersPage,
  AdminPaymentsPage,
  AdminShipmentsPage,
} from './admin-pages';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminLayout,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'orders' },
      { path: 'orders', component: AdminOrdersPage, title: 'Orders · Admin' },
      { path: 'orders/:id', component: AdminOrderDetailPage, title: 'Order · Admin' },
      { path: 'payments', component: AdminPaymentsPage, title: 'Payments · Admin' },
      { path: 'shipments', component: AdminShipmentsPage, title: 'Shipments · Admin' },
      { path: 'inventory', component: AdminInventoryPage, title: 'Inventory · Admin' },
    ],
  },
];
