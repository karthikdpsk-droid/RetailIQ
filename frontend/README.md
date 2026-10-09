# RetailIQ frontend

RetailIQ is a retail demand forecasting and inventory optimization platform. This React application provides a responsive workspace for store operations, inventory, sales, forecast outputs, and recommendations.

## Architecture

```text
Browser → RetailIQ React frontend → Spring Boot API → PostgreSQL
                                      ↕
                                Separate ML service
```

The browser communicates only with Spring Boot. Forecast and recommendation records are read from or ingested through the API; the UI does not depend on the model implementation.

## Stack and structure

- React, TypeScript, Vite, Tailwind CSS
- React Router, Axios, Recharts, Lucide React
- `src/api`: API client and endpoint functions
- `src/context`: authentication state and token persistence
- `src/types`: API domain models
- `src/App.tsx`: routes, application shell, dashboard and operational pages
- `public/_redirects`: static host SPA fallback (Netlify compatible)

## Setup

Requires Node.js 20.19+ or 22.12+ and npm.

```sh
npm install
```

Copy `.env.example` to `.env` for local development and set `VITE_API_BASE_URL` to the Spring Boot host (for example `http://localhost:8080`). The frontend appends `/api/v1`. Do not put credentials or tokens in environment files.

## Development and production

```sh
npm run dev
npm run build
npm run preview
```

Deploy the `dist` directory to Vercel, Netlify, Cloudflare Pages, or Nginx static hosting. Configure `VITE_API_BASE_URL` in the build environment. Configure the host to route unknown paths to `/index.html`; `public/_redirects` covers Netlify. The API must allow the deployed frontend origin through CORS.

## Authentication and API

Public paths are `/login` and `/register`; all other routes require a session. Login and registration use `/api/v1/auth/login` and `/api/v1/auth/register`. The bearer token is stored in local storage and attached by Axios; a 401 clears the session. The API remains responsible for authorization. Registration uses the backend's default SHOPKEEPER role.

Operational pages use the `/api/v1/stores`, `/products`, `/inventory`, `/sales`, `/forecasts`, and `/recommendations` endpoints. The Demand Forecast page can generate a real store-family prediction using `POST /api/v1/forecasts/store-family`; it selects a store and product with an explicit `mlFamilyCode` mapping, then sends only the store, family, and target date. Spring Boot constructs the features and calls the separate FastAPI service. The UI shows the persisted monetary sales forecast, forecast date, prediction cutoff, and model version. It does not translate the forecast into inventory units. The legacy forecast ingest form remains available for backward-compatible result ingestion.

For local development, start PostgreSQL and the API, then run the ML container on port 8000. Set `RETAILIQ_ML_BASE_URL=http://localhost:8000` for the API and `VITE_API_BASE_URL=http://localhost:8080` for Vite. Compose startup instructions and the authenticated real-forecast request are documented in `api-service/README.md`.

## Troubleshooting

- Connection errors: confirm the API is running and `VITE_API_BASE_URL` is set, with CORS configured.
- 401 response: sign in again; the application clears expired sessions.
- Deep links return 404 after deployment: configure the static host SPA fallback to `/index.html`.
- Empty dashboard: the UI shows live API totals only; create records through the API-backed pages.
