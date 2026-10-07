# RetailIQ API

RetailIQ API is the Spring Boot backend for store, product, inventory, sales, forecast, and recommendation data in the Smart Retail Demand Forecasting & Inventory Optimization Platform.

## Architecture

This repository contains one Java API service. The frontend calls this API; a separate ML service owns all prediction and recommendation calculations. Spring Boot does not load ML datasets or calculate predictions. The ML integration flow is:

```text
Frontend -> Spring Boot API -> separate ML service
                              -> forecast/recommendation results
             Spring Boot API stores and serves those results -> Frontend
```

The API exposes ingestion endpoints so externally generated results can be stored. No ML implementation is included here.

## Technology stack

- Java 17 and Spring Boot
- Spring MVC, Spring Data JPA, Spring Security, and Jakarta Validation
- PostgreSQL for application runtime
- JWT bearer authentication
- Springdoc OpenAPI / Swagger UI
- Maven Wrapper

## Prerequisites

- JDK 17
- PostgreSQL 15 or later, or Docker with Docker Compose
- Windows: use `mvnw.cmd`; macOS/Linux: use `./mvnw`

## PostgreSQL and environment configuration

Create a local PostgreSQL database, or use the included Compose setup below. The API accepts these environment variables:

| Variable | Purpose | Default |
|---|---|---|
| `DATABASE_URL` (`DB_URL` fallback) | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/retailiq_db` |
| `DATABASE_USERNAME` (`DB_USERNAME` fallback) | Database user | `postgres` |
| `DATABASE_PASSWORD` (`DB_PASSWORD` fallback) | Database password | `postgres` (local development only) |
| `JWT_SECRET` | Base64 secret decoding to at least 32 bytes | Required |
| `JWT_EXPIRATION` | JWT lifetime in milliseconds | `86400000` |
| `SERVER_PORT` | HTTP port | `8080` |

The local PostgreSQL defaults are for development only. Set strong, environment-specific database credentials and a unique JWT secret in any shared or production deployment. Generate a development JWT secret with `openssl rand -base64 32`; do not commit real credentials or `.env` files. `.env.example` contains placeholders only.

Hibernate is configured with `ddl-auto=update` for this project. Review and replace that setting with a managed schema migration process before production deployment.

## Run locally

1. Start PostgreSQL and create the database configured by `DATABASE_URL` (default database name: `retailiq_db`).
2. Set `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `JWT_SECRET` in your shell. The JWT secret must be Base64 and decode to at least 32 bytes.
3. Start the API:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   On macOS/Linux, run `./mvnw spring-boot:run`.

Swagger UI is available at `http://localhost:8080/swagger-ui.html`; the OpenAPI document is at `http://localhost:8080/v3/api-docs`. Health endpoints are `GET /api/v1/health` and `/actuator/health`.

## API modules

- **Authentication:** `POST /api/v1/auth/register`, `POST /api/v1/auth/login`
- **Stores:** `POST|GET /api/v1/stores`, `GET|PUT|DELETE /api/v1/stores/{id}`
- **Products:** `POST|GET /api/v1/products`, `GET|PUT|DELETE /api/v1/products/{id}`
- **Inventory:** `POST|GET /api/v1/inventory`, `GET|PUT|DELETE /api/v1/inventory/{id}`, `PATCH /api/v1/inventory/{id}/stock`
- **Sales:** `POST|GET /api/v1/sales`, `GET /api/v1/sales/{id}`, `GET /api/v1/sales/store/{storeId}`, `GET /api/v1/sales/product/{productId}`
- **Forecasts:** `POST /api/v1/forecasts/ingest`, `GET /api/v1/forecasts`, `GET /api/v1/forecasts/{id}`, `GET /api/v1/forecasts/store/{storeId}`, `GET /api/v1/forecasts/product/{productId}`
- **Recommendations:** `POST /api/v1/recommendations/ingest`, `GET /api/v1/recommendations`, `GET /api/v1/recommendations/{id}`, `GET /api/v1/recommendations/store/{storeId}`, `GET /api/v1/recommendations/product/{productId}`

Forecast and recommendation ingestion stores supplied results; it does not calculate them. Both ingest endpoints require JWT authentication and allow `ADMIN` or `SHOPKEEPER`. A shopkeeper can ingest only for stores and products they own, and the product must belong to the requested store. Treat shopkeeper ingestion as a development/testing integration path; use a separately authenticated ML-service identity or equivalent trusted integration boundary before accepting untrusted production writes.

## Authentication and authorization

Register with `POST /api/v1/auth/register`; public registration always creates a `SHOPKEEPER` and cannot grant `ADMIN`. Log in with `POST /api/v1/auth/login`, then send the returned token as `Authorization: Bearer <token>` for secured endpoints. Passwords are BCrypt hashed. Shopkeepers can access only resources belonging to their stores; admins can access resources across owners. Provision an administrator through a trusted administrative process, never through public registration.

## Tests and package build

Tests use an isolated in-memory H2 database in PostgreSQL compatibility mode; they do not connect to the configured PostgreSQL database.

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package
```

On macOS/Linux, use `./mvnw clean test` and `./mvnw package`.

## Docker Compose

Copy `.env.example` to `.env`, replace the placeholders with local development values, then run `docker compose up --build`. Compose starts PostgreSQL and the API. Stop the containers with `docker compose down`. `docker compose down -v` removes the local PostgreSQL data volume; do not use it if you need to retain that data.
