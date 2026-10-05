# fulfillment-service

Picks and packs confirmed orders. Fully event-driven; a simulator advances the status with configurable delays and failure rate.

| | |
|---|---|
| Port | 8084 |
| Storage | PostgreSQL `fulfillment_db` |

## Endpoints

- No public endpoints (internal only)

## Notes

- Consumes `order-events` (`ORDER_CONFIRMED`)
- Publishes `fulfillment-events` through the outbox

**Status:** not implemented yet. See `../../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../../data-model/`.
