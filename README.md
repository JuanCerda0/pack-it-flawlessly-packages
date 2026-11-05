# Pack-it-flawlessly Backend

Spring Boot REST API for managing packages and their contents. The code is organized by responsibility under `dev.noby.packit`: controllers, DTOs, entities, mapper, repository, services, security, configuration, and exceptions.

## Run locally

Requires Java 21 and PostgreSQL. Create an empty database named `packit_packages` with the local `postgres` user, for example `createdb -h localhost -U postgres packit_packages`. The default connection uses `postgres` with no password; set `PACKIT_DB_PASSWORD` if your local installation requires one. A `.env.local` file is optional and ignored by Git; keep it private (`chmod 600 .env.local`) if it contains credentials. If you use it, run `set -a; . ./.env.local; set +a` before the commands below.

```bash
./mvnw spring-boot:run
```

The API listens on port `8080` by default. Flyway creates the `packages` and `package_items` tables on first startup, and Hibernate validates the schema. The new database starts empty; the previous H2 data is not migrated. To load optional sample data, run `psql -h localhost -U postgres -d packit_packages -f scripts/seed-demo-data.sql` after the first startup.

Use `./mvnw test` to run the PostgreSQL integration tests. Tests replace the JWT decoder only in the test context, so they do not need to contact Cognito; runtime authorization is unchanged. Set `PACKIT_DB_URL`, `PACKIT_DB_USERNAME`, and `PACKIT_DB_PASSWORD` as needed for other PostgreSQL locations.

Flyway also creates `outbox_events`. Package changes and their pending messages are saved in one transaction; transitions to `PREPARING` and `READY` add document jobs. See [message contracts](docs/message-contracts.md).

Start RabbitMQ locally before running the API. On the first RabbitMQ connection, Spring declares the durable classic work queues, their DLQs, and the exchanges and bindings. Every five seconds, the outbox publisher sends pending messages as persistent JSON. It marks a row published only after a broker confirmation and successful routing; connection failures or unroutable messages remain pending for retry. The API can still start while RabbitMQ is unavailable. Historial and Documentos consume the three work queues when those services are running.

The default broker connection is `localhost:5672` with RabbitMQ's local `guest` account. Override `PACKIT_RABBITMQ_HOST`, `PACKIT_RABBITMQ_PORT`, `PACKIT_RABBITMQ_USERNAME`, and `PACKIT_RABBITMQ_PASSWORD` for another broker. `PACKIT_OUTBOX_PUBLISH_DELAY_MS`, `PACKIT_OUTBOX_BATCH_SIZE`, and `PACKIT_OUTBOX_CONFIRM_TIMEOUT_MS` tune the publisher. Run `./mvnw -q -Dpackit.rabbit.integration=true -Dtest=OutboxRabbitIntegrationTest test` to verify confirms, routing, and retry against a running local RabbitMQ; the normal test suite does not require it.

The Cognito issuer and CORS origins can be changed with `COGNITO_ISSUER_URI` and `PACKIT_CORS_ALLOWED_ORIGINS`. The latter accepts a comma-separated list; local development defaults to `http://localhost:4200`.

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

## Full local stack with Docker Compose

The [Compose file](compose.yaml) builds this API plus the sibling Historial, Documentos, and Administrador repositories. Keep all four repositories next to each other under the same workspace directory. The Angular frontend is not included. Three PostgreSQL 18 containers each own one database; two RabbitMQ 4.1 nodes form one cluster. The Administrator has no database.

```bash
cp .env.compose.example .env.compose
# Replace every placeholder in .env.compose with local passwords and a shared Erlang cookie.
chmod 600 .env.compose
docker compose --env-file .env.compose config -q
docker compose --env-file .env.compose up -d --build
docker compose --env-file .env.compose ps
docker compose --env-file .env.compose exec rabbit1 rabbitmqctl cluster_status
```

On this Arch installation, Docker may require `sudo docker compose` instead of `docker compose`. Do not print `docker compose config` without `-q`: the expanded output contains passwords. The private `.env.compose` is ignored by Git; the committed example contains placeholders only.

| Component | Host access | Container address used by the APIs |
| --- | --- | --- |
| RabbitMQ node 1 | AMQP `localhost:5673`, console `http://localhost:15673` | `rabbit1:5672` |
| RabbitMQ node 2 | AMQP `localhost:5674`, console `http://localhost:15674` | `rabbit2:5672` |
| Paquetes PostgreSQL | `localhost:5433`, database `packit_packages` | `postgres-packages:5432` |
| Historial PostgreSQL | `localhost:5434`, database `packit_history` | `postgres-history:5432` |
| Documentos PostgreSQL | `localhost:5435`, database `packit_documents` | `postgres-documents:5432` |
| APIs | `localhost:8080`, `:8082`, `:8083`, `:8084` | Same port on the Compose network |

All host ports bind to `127.0.0.1`, so the system PostgreSQL on 5432 and RabbitMQ on 5672 can remain running. Override host ports with `PACKIT_*_PORT` variables if necessary. The four APIs connect to `rabbit1`; RabbitMQ routes to queues hosted by either cluster node. `rabbit2` joins `rabbit1` using [rabbit2.conf](infra/rabbitmq/rabbit2.conf) and the shared cookie. Check that `cluster_status` lists both `rabbit@rabbit1` and `rabbit@rabbit2`; two running containers alone do not prove clustering.

Named volumes preserve each PostgreSQL instance and RabbitMQ node across `docker compose down` and restarts. They are separate from the databases and broker installed on the host; this stack starts with fresh data. Compose waits for healthy databases and both RabbitMQ nodes before starting the APIs. Flyway then creates each database schema. The default Cognito issuer is the existing PackIt user pool; override `COGNITO_ISSUER_URI` in `.env.compose` when using another issuer. Access tokens still require `ADMIN` or `SUPERVISOR` groups.

```bash
docker compose --env-file .env.compose logs -f packages history documents administrator
docker compose --env-file .env.compose down
```

The work queues remain `classic` and durable. A two-node cluster shares topology but does not replicate their message contents; this setup demonstrates the required cluster, not queue failover.
