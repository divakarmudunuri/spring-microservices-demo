// JSON shapes of the backend APIs this app calls (through nginx at /api).
// Kept by hand, like every other consumer in this repo: there's no shared DTO library.

export type Availability = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK';

export interface Category {
  id?: string;
  slug: string;
  name: string;
}

/** A product as the storefront sees it: an availability level, never a stock count. */
export interface Product {
  id: string;
  sku?: string;
  slug: string;
  name: string;
  description: string;
  category: Category;
  imageUrl: string;
  price: number;
  currency: string;
  featured: boolean;
  availability: Availability;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Sections a composed response could not fill (e.g. "featured", "shipping"). */
export interface Degradable {
  degraded: boolean;
  unavailableSections: string[];
}

export interface StorefrontHome extends Degradable {
  categories: Category[];
  featured: Product[];
  newArrivals: Product[];
}

export interface StorefrontProductPage extends Degradable {
  product: Product;
  related: Product[];
  categories: Category[];
}

export interface CartLine {
  productId: string;
  quantity: number;
  /** null while product-service is unavailable (degraded cart). */
  name: string | null;
  slug: string | null;
  imageUrl: string | null;
  unitPrice: number | null;
  availability: Availability | null;
  lineTotal: number | null;
}

export interface Cart {
  cartId: string;
  guest: boolean;
  version: number;
  lines: CartLine[];
  subtotal: number | null;
  currency: string;
  degraded: boolean;
}

export interface Address {
  id?: string;
  fullName: string;
  line1: string;
  line2?: string | null;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  phone?: string | null;
}

export type Role = 'CUSTOMER' | 'ADMIN';

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  status: 'ACTIVE' | 'SUSPENDED';
  defaultAddress: Address | null;
}

export interface WalletEntry {
  type: 'TOP_UP' | 'PAYMENT' | 'REFUND' | string;
  amount: number;
  orderId: string | null;
  at: string;
}

export interface Wallet {
  userId: string;
  balance: number;
  currency: string;
  recentTransactions: WalletEntry[];
}

export type OrderStatus =
  | 'INITIATED'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'FAILED'
  | 'IN_FULFILLMENT'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'COMPLETED'
  | 'CANCELLED';

export interface OrderSummary {
  id: string;
  userId: string;
  status: OrderStatus;
  rejectionReason: string | null;
  totalAmount: number | null;
  currency: string;
  createdAt: string;
}

export interface Order {
  id: string;
  status: OrderStatus;
  rejectionReason: string | null;
  totalAmount: number | null;
  currency: string;
  items: { productId: string; quantity: number; unitPrice: number | null }[];
  shippingAddress: Address | null;
  cartId: string | null;
  deliveryAcknowledgedAt: string | null;
  createdAt: string;
}

export interface TimelineEntry {
  status: string;
  source: string;
  occurredAt: string;
  details: Record<string, string> | null;
}

export interface OrderDetails extends Degradable {
  id: string;
  status: OrderStatus;
  totalAmount: number | null;
  currency: string;
  createdAt: string;
  shippingAddress: Address | null;
  items: { productId: string; name: string | null; description: string | null; quantity: number; unitPrice: number | null }[];
  customer: { fullName: string; email: string } | null;
  shipment: {
    trackingNumber: string;
    carrier: string;
    status: string;
    estimatedDelivery: string | null;
    deliveredAt: string | null;
  } | null;
  tracking: { currentStatus: string; timeline: TimelineEntry[] } | null;
}

export interface Payment {
  id: string;
  orderId: string;
  userId: string;
  amount: number;
  currency: string;
  status: 'CAPTURED' | 'REFUNDED' | string;
  createdAt: string;
  refundedAt: string | null;
}

export interface PaymentsReport {
  payments: Page<Payment>;
  totalsByStatus: { status: string; count: number; amount: number }[];
}

export interface Shipment {
  id: string;
  orderId: string;
  userId: string;
  trackingNumber: string;
  carrier: string;
  status: string;
  estimatedDelivery: string | null;
  deliveredAt: string | null;
  shippingAddress: Address | null;
  createdAt: string;
}

export interface InventoryItem {
  productId: string;
  name: string | null;
  quantityOnHand: number;
  updatedAt: string;
}

export interface Inventory {
  items: InventoryItem[];
  namesAvailable: boolean;
}

/** RFC 7807 error body, from the services or from nginx. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  /** nginx adds this to its 401s: where to start the sign-in. */
  loginUrl?: string;
  /** order-service adds this to checkout errors, so the order can still be looked up. */
  orderId?: string;
}
