# discovery-server

Eureka service registry. Every other backend service registers here and finds the others by name.

| | |
|---|---|
| Port | 8761 |
| Storage | None |

## Endpoints

- Eureka dashboard at http://localhost:8761 (local only)

## Notes

- Self-registration disabled
- Not exposed through nginx

**Status:** not implemented yet. See `../../CLAUDE.md` for the full spec and the implementation phases. Data model: `../../data-model/`.
