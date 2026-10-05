package com.smd.cartservice.cart;

import com.smd.cartservice.client.ProductsUnavailableException;
import com.smd.cartservice.client.product.ProductAdapter;
import com.smd.cartservice.client.product.ProductInfo;
import com.smd.cartservice.config.CartProperties;
import com.smd.cartservice.persistence.CartConflictException;
import com.smd.cartservice.persistence.CartItem;
import com.smd.cartservice.persistence.CartLineItem;
import com.smd.cartservice.persistence.CartRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Guest and customer carts (CLAUDE.md 6.12). Carts hold only product ids and quantities; prices are looked up live.
 * Adding to a cart does not reserve stock: checkout is the authoritative check.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository carts;
    private final ProductAdapter products;
    private final CartProperties properties;
    private final Clock clock;

    public CartService(CartRepository carts, ProductAdapter products, CartProperties properties, Clock clock) {
        this.carts = carts;
        this.products = products;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- reading -------------------------------------------------------------------------------

    /** A guest's cart by its id, or the customer's own cart (created empty the first time). */
    public CartView view(CartOwner owner) {
        return toView(load(owner));
    }

    /** {@code POST /api/cart} without a token: a new, empty guest cart. */
    public CartView createGuestCart() {
        CartItem cart = newCart(CartIds.newGuestCartId(), null);
        carts.create(cart);
        log.debug("Created guest cart {}", CartIds.mask(cart.getCartId()));
        return toView(cart);
    }

    // ---- changing --------------------------------------------------------------------------------

    /** Adds to an existing line, or a new line. Out-of-stock products are refused (409); limits → 422. */
    public CartView addItem(CartOwner owner, UUID productId, int quantity) {
        ProductInfo product = lookup(productId);
        if (product.outOfStock()) {
            throw new ProductUnavailableException(product.name() + " is out of stock");
        }
        return toView(change(owner, cart -> setQuantity(cart, productId, quantityOf(cart, productId) + quantity)));
    }

    /** Sets a line's quantity; 0 removes it. */
    public CartView updateItem(CartOwner owner, UUID productId, int quantity) {
        return toView(change(owner, cart -> setQuantity(cart, productId, quantity)));
    }

    public CartView removeItem(CartOwner owner, UUID productId) {
        return toView(change(owner, cart -> setQuantity(cart, productId, 0)));
    }

    public void clear(CartOwner owner) {
        carts.delete(load(owner).getCartId());
    }

    /**
     * After sign-in: the guest cart's lines are added to the customer's cart (quantities summed, capped at the
     * per-product limit; lines beyond the line limit are dropped), and the guest cart is deleted, in one atomic write.
     * Idempotent: an already merged, expired or unknown guest cart leaves the customer's cart as it is.
     */
    public CartView merge(UUID customerId, String guestCartId) {
        for (int attempt = 1; ; attempt++) {
            CartItem customerCart = customerCart(customerId);
            Optional<CartItem> guest = carts.find(guestCartId).filter(c -> c.getOwnerUserId() == null);
            if (guest.isEmpty()) {
                return toView(customerCart);
            }
            for (CartLineItem line : guest.get().getItems()) {
                UUID productId = UUID.fromString(line.getProductId());
                int merged = Math.min(quantityOf(customerCart, productId) + line.getQuantity(), properties.maxQuantity());
                boolean newLine = quantityOf(customerCart, productId) == 0;
                if (newLine && customerCart.getItems().size() >= properties.maxLines()) {
                    continue;   // the customer's cart is full: this guest line is dropped
                }
                put(customerCart, productId, merged);
            }
            touch(customerCart);
            try {
                if (carts.mergeAndDeleteGuest(customerCart, guestCartId)) {
                    log.debug("Merged guest cart {} into the customer's cart", CartIds.mask(guestCartId));
                }
                return toView(carts.find(customerCart.getCartId()).orElse(customerCart));
            } catch (CartConflictException e) {
                if (attempt == 2) {
                    throw new CartBusyException("The cart is being changed elsewhere; please try again");
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Read, change, write if unchanged; one retry on a concurrent change, then 409. */
    private CartItem change(CartOwner owner, Consumer<CartItem> modification) {
        for (int attempt = 1; ; attempt++) {
            CartItem cart = load(owner);
            modification.accept(cart);
            touch(cart);
            try {
                carts.save(cart);
                return cart;
            } catch (CartConflictException e) {
                if (attempt == 2) {
                    throw new CartBusyException("The cart is being changed elsewhere; please try again");
                }
            }
        }
    }

    private CartItem load(CartOwner owner) {
        if (owner.isCustomer()) {
            return customerCart(owner.customerId());
        }
        if (owner.guestCartId() == null) {
            throw new CartNotFoundException("No cart: create one with POST /api/cart and send its id in X-Cart-Id");
        }
        // a guest may only use guest carts: a customer's cart is never reachable by its id
        return carts.find(owner.guestCartId()).filter(c -> c.getOwnerUserId() == null)
                .orElseThrow(() -> new CartNotFoundException("No such cart"));
    }

    /**
     * The customer's single cart, looked up through the {@code byOwner} index. If it isn't there (no cart yet, or the
     * index hasn't caught up, since GSI reads are eventually consistent), creating it with the deterministic id either
     * succeeds or tells us it already exists, and then we read it by its key.
     */
    private CartItem customerCart(UUID customerId) {
        Optional<CartItem> existing = carts.findByOwner(customerId.toString());
        if (existing.isPresent()) {
            return existing.get();
        }
        CartItem cart = newCart(CartIds.customerCartId(customerId), customerId.toString());
        if (carts.create(cart)) {
            return cart;
        }
        return carts.find(cart.getCartId()).orElseThrow();
    }

    private CartItem newCart(String cartId, String ownerUserId) {
        CartItem cart = new CartItem();
        cart.setPk(CartItem.pk(cartId));
        cart.setCartId(cartId);
        cart.setOwnerUserId(ownerUserId);
        cart.setCreatedAt(clock.instant().toString());
        touch(cart);
        return cart;
    }

    /** Every write pushes the TTL forward: guest carts live 7 days after their last change, customer carts 30. */
    private void touch(CartItem cart) {
        Instant now = clock.instant();
        Duration ttl = cart.getOwnerUserId() == null ? properties.guestTtl() : properties.customerTtl();
        cart.setUpdatedAt(now.toString());
        cart.setExpiresAt(now.plus(ttl).getEpochSecond());
    }

    private void setQuantity(CartItem cart, UUID productId, int quantity) {
        if (quantity > properties.maxQuantity()) {
            throw new CartLimitException("At most " + properties.maxQuantity() + " of one product");
        }
        if (quantity > 0 && quantityOf(cart, productId) == 0 && cart.getItems().size() >= properties.maxLines()) {
            throw new CartLimitException("At most " + properties.maxLines() + " different products in a cart");
        }
        put(cart, productId, quantity);
    }

    private void put(CartItem cart, UUID productId, int quantity) {
        List<CartLineItem> items = cart.getItems();
        items.removeIf(l -> l.getProductId().equals(productId.toString()) && quantity <= 0);
        for (CartLineItem line : items) {
            if (line.getProductId().equals(productId.toString())) {
                line.setQuantity(quantity);
                return;
            }
        }
        if (quantity > 0) {
            items.add(new CartLineItem(productId.toString(), quantity, clock.instant().toString()));
        }
    }

    private static int quantityOf(CartItem cart, UUID productId) {
        return cart.getItems().stream().filter(l -> l.getProductId().equals(productId.toString()))
                .mapToInt(CartLineItem::getQuantity).findFirst().orElse(0);
    }

    private ProductInfo lookup(UUID productId) {
        ProductInfo product = products.getProducts(List.of(productId)).get(productId);
        if (product == null) {
            throw new UnknownProductException("Product " + productId + " is not in the catalog");
        }
        return product;
    }

    /** The current price and level of every line, from one batch call. Degraded, not failed, without product-service. */
    private CartView toView(CartItem cart) {
        List<UUID> ids = cart.getItems().stream().map(l -> UUID.fromString(l.getProductId())).toList();
        Map<UUID, ProductInfo> info;
        boolean degraded = false;
        try {
            info = ids.isEmpty() ? Map.of() : products.getProducts(ids);
        } catch (ProductsUnavailableException e) {
            info = Map.of();
            degraded = true;
            log.warn("Cart view without product data: {}", e.getMessage());
        }
        Map<UUID, ProductInfo> found = info;
        List<CartView.Line> lines = cart.getItems().stream().map(l -> {
            UUID id = UUID.fromString(l.getProductId());
            ProductInfo p = found.get(id);
            BigDecimal lineTotal = p == null ? null : p.price().multiply(BigDecimal.valueOf(l.getQuantity()));
            return new CartView.Line(id, l.getQuantity(), p == null ? null : p.name(), p == null ? null : p.slug(),
                    p == null ? null : p.imageUrl(), p == null ? null : p.price(), p == null ? null : p.availability(),
                    lineTotal);
        }).toList();
        BigDecimal subtotal = degraded ? null
                : lines.stream().map(CartView.Line::lineTotal).filter(t -> t != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartView(cart.getCartId(), cart.getOwnerUserId() == null, cart.getVersion(), lines, subtotal, "USD", degraded);
    }
}
