# RetailIQ

RetailIQ API is the Spring Boot backend for store, product, inventory, sales, forecast, and recommendation data in the Smart Retail Demand Forecasting & Inventory Optimization Platform.

## Architecture

The repository contains the Spring Boot API at its root, the React application in `frontend/`, and the FastAPI model service in `ml-service/`. Docker Compose builds and runs all three application services with PostgreSQL. The React frontend calls the API; the ML service owns predictions. Spring Boot builds leakage-safe features, calls the model service, validates the result, and persists forecasts in PostgreSQL. It does not load the model or calculate predictions. The ML integration flow is:

```text
React frontend -> Spring Boot API -> FastAPI ML service -> trained Python model
                  |                                     <- monetary forecast
                  +-> PostgreSQL: StoreFamilyForecast
React frontend <- authenticated Spring Boot forecast response
```

Legacy forecast and recommendation ingestion endpoints remain available for existing clients. ML forecasting uses the separate store-family endpoint documented below.

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
| `RETAILIQ_ML_BASE_URL` (`ML_SERVICE_BASE_URL` fallback) | FastAPI ML service base URL | `http://localhost:8000` |
| `RETAILIQ_ML_CONNECT_TIMEOUT` | ML connection timeout | `2s` |
| `RETAILIQ_ML_READ_TIMEOUT` | ML response timeout | `10s` |
| `ML_SERVICE_PORT` | Host port for ML service in Compose | `8000` |

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

Copy `.env.example` to `.env`, replace the placeholders with local development values, then run `docker compose up --build`. Compose starts PostgreSQL, API, and ML services. Stop the containers with `docker compose down`. `docker compose down -v` removes the local PostgreSQL data volume; do not use it if you need to retain that data.

## ML forecast integration

The React frontend calls Spring Boot; Spring owns feature construction and persistence. FastAPI remains a separate Python service and loads the `demand_forecast_v1.0.2` model artifact. Flow: React -> authenticated Spring API -> FastAPI `POST /forecast` -> model -> Spring response and PostgreSQL `StoreFamilyForecast` -> React display.

Spring uses `RETAILIQ_ML_BASE_URL` (or `ML_SERVICE_BASE_URL` as a fallback), `RETAILIQ_ML_CONNECT_TIMEOUT` (default `2s`), and `RETAILIQ_ML_READ_TIMEOUT` (default `10s`). For Spring running on the host use `http://localhost:8000`. The ML image can also be started independently with `docker run --rm -p 8000:8000 --name retailiq-ml retailiq-ml:1.0` after building it from `ml-service` using `docker build -t retailiq-ml:1.0 .`.

The React forecasts page posts only business identifiers and a date; it never calculates model features:

```http
POST /api/v1/forecasts/store-family
Authorization: Bearer <JWT>
Content-Type: application/json
X-Request-ID: optional-client-request-id
```

```json
{"storeId": 1, "mlFamily": "AUTOMOTIVE", "forecastDate": "2017-01-01"}
```

Products must carry an explicit `mlFamilyId`; `mlFamilyCode` is returned by the product API for frontend selection. Product category is not an ML-family mapping. The API forwards the complete feature request to `POST /forecast` only after loading cutoff-safe sales, promotion, holiday, and oil inputs. It validates the ML response before persisting and returning `forecastDemand`, `demandUnit` (`MONETARY_SALES`), `modelVersion`, target date, and prediction cutoff. This is monetary sales, not physical units; forecasts do not update inventory. The ML service's live OpenAPI contract is available at `http://localhost:8000/docs`; its health endpoint is `http://localhost:8000/health`.

Spring logs the request ID, store, family, target date, model version when available, duration, and outcome. It does not log tokens, passwords, or authorization headers.

### Full API, ML, and database Compose

Copy `.env.example` to `.env`, replace the local placeholder secrets, and run from the repository root:

```powershell
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:8000/health
```

The Compose services are PostgreSQL (`5432`), ML FastAPI (`8000`), Spring API (`8080`), and frontend (`80`). The API reaches ML at `http://ml-service:8000`. For frontend development with hot reload, run `npm install` and `npm run dev` from `frontend`, setting `VITE_API_BASE_URL=http://localhost:8080`.

For a real forecast flow, first use valid database records: an authenticated user; an owned store with ML store number and cluster; an explicitly mapped ML family; sufficient or contract-valid historical sales; a promotion plan; and source coverage for the forecast date. Then set the returned JWT and IDs in the shell and run:

```powershell
$headers = @{ Authorization = "Bearer $env:RETAILIQ_JWT"; 'X-Request-ID' = 'retailiq-e2e-001' }
$body = @{ storeId = [int]$env:RETAILIQ_STORE_ID; mlFamily = $env:RETAILIQ_ML_FAMILY; forecastDate = $env:RETAILIQ_FORECAST_DATE } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/forecasts/store-family -Headers $headers -ContentType 'application/json' -Body $body
$created
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/forecasts/store-family/store/$env:RETAILIQ_STORE_ID" -Headers $headers
```

This repository does not seed operational transactions, promotions, or store mappings. Use valid existing records; no sample fixture or stub response demonstrates production E2E. The React page displays the API-created forecast and model version. `GET /api/v1/forecasts/store-family/store/{storeId}` retrieves persisted store-family forecasts subject to ownership checks.

Troubleshooting: `503` from Spring usually means FastAPI is unavailable or timed out; `502` indicates ML rejected the request or returned an invalid response; a feature-data error means a required historical/source value or mapping is unavailable. Check `GET /health` on ML, Compose health status, API logs by `X-Request-ID`, the store ML mappings, promotion availability by cutoff, and oil/holiday date coverage.

### Validation run (2026-10-08)

Commands and results from this workspace:

```powershell
cd api-service
.\mvnw.cmd clean test
# BUILD SUCCESS — 53 tests, 0 failures/errors

cd ..\ml-service
python -m pytest -q
# 66 passed

cd ..\frontend
npm run build
# success (Vite emitted its existing large-chunk advisory)

cd ..\api-service
$env:ML_SERVICE_PORT='8001'
docker compose up --build -d
# API, PostgreSQL, and ML containers started; PostgreSQL and ML health checks passed
```

Compose health check returned model `demand_forecast_v1.0.2`; API `GET /api/v1/health` returned `RetailIQ API is running`. The real model request was sent to the Compose FastAPI container with the checked-in `ml-service/forecast-test.json`:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8001/forecast -InFile ..\ml-service\forecast-test.json -ContentType 'application/json'
# store_nbr=1, family=AUTOMOTIVE, date=2017-01-01,
# forecast_demand=23.40468100419723, horizon=1, model=demand_forecast_v1.0.2
```

That initial validation used an empty Compose database, so it did not run the authenticated Spring forecast. A later final E2E run created temporary test records through the existing authenticated API and used the existing model; its complete result is recorded below.

When the standalone ML container already occupies host port 8000, set `ML_SERVICE_PORT=8001` in the shell or `.env` before Compose startup. The API-to-ML address inside Compose remains `http://ml-service:8000`.

### Final authenticated E2E result (2026-10-08)

The complete flow passed using a fresh project-scoped Compose database. The standalone ML container remained on host port 8000; the Compose ML service used host port 8002, API used 8081, and PostgreSQL used 5434. Startup used `docker compose -p retailiq-e2e-final up --build -d` with temporary shell-only `POSTGRES_PASSWORD` and `JWT_SECRET` values (not written to files).

The test registered a SHOPKEEPER through `POST /api/v1/auth/register`, used that response JWT, created an owned Quito store and product through the API, mapped the product explicitly to `AUTOMOTIVE`, created inventory, submitted 28 daily sales from 2016-12-04 through 2016-12-31, and added an `on_promotion=0` plan available before the 2016-12-31 cutoff. The store mapping used `ml_store_nbr=1` and `ml_cluster=13`. It then called `POST /api/v1/forecasts/store-family` with request ID `retailiq-e2e-final-eb35a9343b` for 2017-01-01.

Spring called the actual FastAPI `POST /forecast`; the Compose ML health endpoint reported `demand_forecast_v1.0.2`, and the ML container logged `POST /forecast HTTP/1.1` with status 200. Its prediction event for the same request ID recorded the unrounded Random Forest result `87.59375000783588`. Spring logged store 1, family `AUTOMOTIVE`, forecast date, `latency_ms=207`, `outcome=SUCCESS`, and model version `demand_forecast_v1.0.2`. At the database/API contract scale, the monetary forecast is `87.5938`. Spring persisted PostgreSQL row `1|1|AUTOMOTIVE|2017-01-01|2016-12-31|87.5938|MONETARY_SALES|demand_forecast_v1.0.2`; the POST response and authenticated GET `/api/v1/forecasts/store-family/store/1` returned the same forecast ID and value.

The first E2E attempt exposed a response precision mismatch: the model's full precision value was returned from POST while PostgreSQL stores four decimal places. Spring now rounds the value to the declared database scale before persistence and returns that persisted value. The regression test covers this behavior, the API suite passed again (53 tests), and the complete real-model E2E was rerun successfully.
