# Pack-it-flawlessly Backend

Spring Boot REST API for managing packages and their contents. The code is organized by responsibility under `dev.noby.packit`: controllers, DTOs, entities, mapper, repository, services, security, configuration, and exceptions.

## Run locally

Requires Java 21 and PostgreSQL. Create a database named `packit_packages` owned by a dedicated login role `packit_packages_app`. Set a password for that role, then copy `.env.example` to `.env.local` and fill in `PACKIT_DB_PASSWORD`. The local file is ignored by Git; keep it private (`chmod 600 .env.local`).

```bash
set -a
. ./.env.local
set +a
./mvnw spring-boot:run
```

The API listens on port `8080` by default. Flyway creates the `packages` and `package_items` tables on first startup, and Hibernate validates the schema. The new database starts empty; the previous H2 data is not migrated. To load optional sample data, run `psql -h localhost -U packit_packages_app -d packit_packages -f scripts/seed-demo-data.sql` after the first startup.

Use `./mvnw test` with the same environment variables to run the PostgreSQL integration tests. Tests replace the JWT decoder only in the test context, so they do not need to contact Cognito; runtime authorization is unchanged. Set `PACKIT_DB_URL`, `PACKIT_DB_USERNAME`, and `PACKIT_DB_PASSWORD` for other PostgreSQL locations.

The Cognito issuer and CORS origins can be changed with `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and `PACKIT_CORS_ALLOWED_ORIGINS`. The latter accepts a comma-separated list; local development defaults to `http://localhost:4200`.

## API routes

| Method | Route | Permission |
|---|---|---|
| `GET` | `/api/v1/packages` | Cognito group `ADMIN` or `SUPERVISOR` |
| `POST` | `/api/v1/packages` | Cognito group `ADMIN` |
| `GET` | `/api/v1/packages/{packageId}` | Cognito group `ADMIN` or `SUPERVISOR` |
| `PUT` | `/api/v1/packages/{packageId}` | Cognito group `ADMIN` |
| `PATCH` | `/api/v1/packages/{packageId}/status` | Cognito group `ADMIN` |
| `GET` | `/api/v1/packages/{packageId}/items` | Cognito group `ADMIN` or `SUPERVISOR` |
| `POST` | `/api/v1/packages/{packageId}/items` | Cognito group `ADMIN` |
| `PUT` | `/api/v1/packages/{packageId}/items/{itemId}` | Cognito group `ADMIN` |
| `GET` | `/api/v1/dashboard/summary` | Cognito group `ADMIN` or `SUPERVISOR` |

Packages use a soft-delete lifecycle: set status to `ARCHIVED`. Status progression is `RECEIVED → PREPARING → READY → IN_TRANSIT → DELIVERED`; any non-archived package can be archived. Archived packages cannot be edited or reactivated.

## Authentication

Spring Security runs as an OAuth2 resource server and validates Cognito access tokens. It maps `cognito:groups` to Spring roles: `ADMIN` can read and mutate; `SUPERVISOR` can read. The backend rejects ID tokens by checking the Cognito `token_use` claim. OAuth scopes are not used by the backend for RBAC in this milestone. API Gateway can require `packit-api/read` on methods to validate access tokens; the backend remains responsible for differentiating permissions by group.
