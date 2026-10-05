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

## Run

```bash
./gradlew :discovery-server:bootRun --args='--spring.profiles.active=local'
```

In `local`, self-preservation is off and stopped instances are evicted within seconds.

**Status:** implemented (phase 3). See `../../CLAUDE.md` for the full spec.
