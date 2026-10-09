# RetailIQ ML Service

## Architecture and data

This service forecasts next-day monetary `sales` per `store_nbr + family`. Feature construction is in `src/features/feature_engineering.py`; the exact 25 model columns and chronological training code are in `src/forecasting.py`. Large training datasets are not included in this repository; restore the approved corrected dataset at `data/processed/retailiq_leakage_safe_corrected.csv` before running training or historical replay scripts. Inference uses the versioned model artifact and does not require those datasets.

Sales lags use exact prior calendar dates and rolling windows end before the target date. Target-day sales, same-day transactions, unaudited transaction lags, future observations and DBSCAN labels are excluded. DBSCAN remains exploratory. Missing history remains missing until the fitted preprocessing imputer handles it. Known calendar/holiday/promotion information is assumed available at the forecast cutoff; oil has a conservative two-day buffer because release timestamps are unavailable.

Training uses dates before 2016-07-01; validation covers 2016-07-01 through 2016-12-31; the final test begins 2017-01-01. The selected model is a fixed-seed Random Forest; the existing artifact is `models/demand_forecast_v1.0.2.joblib`. Its recorded validation MAE/RMSE/R2 are 61.45/298.72/0.9468; historical held-out test values are 74.69/306.13/0.9490. Training fits a reproducible sample capped at 60,000 rows. These are historical evaluations, not production performance claims. Prediction intervals are not calibrated.

## Inventory policy

`src/inventory/optimization.py` owns the inventory calculations; defaults are in `configs/inventory.yaml`. The policy uses dated historical sales for variability, lead time, review period, safety stock, reorder point and target stock. It never invents on-hand inventory. Without stock data it returns an unknown inventory status and no order quantity. Source `sales` is monetary rather than physical units, so all current recommendations are sales-equivalent. Physical unit inventory optimization requires physical quantity demand and stock data. The documented historical replay is a simulation, not production performance.

## FastAPI serving

Start from this directory:

```powershell
uvicorn src.serving.app:app --host 0.0.0.0 --port 8000
```

- `GET /health`: model readiness and version.
- `POST /forecast`: validated one-day point forecast from the exact serialized feature contract.
- `POST /inventory/optimize`: delegates to the existing inventory policy.
- `/docs` and `/openapi.json`: generated API documentation.

### Windows App Control note

On some Windows machines, the SciKit-Learn native extension modules may be blocked by application-control policies or the local file-lock metadata. If importing `sklearn` fails with a DLL load error such as `An Application Control policy has blocked this file`, unblock the `.pyd` files in the project virtual environment or run the project from a fresh virtual environment created with the system interpreter. A local bootstrap at `sitecustomize.py` is included to remove the Windows `Zone.Identifier` stream from the bundled native binaries before `sklearn` initializes.

Serving loads v1.0.2 once per process. The live OpenAPI contract is available at `/docs` and `/openapi.json`. Errors are structured and safe; request IDs are returned in `X-Request-ID`. CORS is disabled unless explicit origins are configured through `RETAILIQ_CORS_ORIGINS`; wildcard origins are rejected. The ML API is ready for a Spring Boot client to integrate over HTTP in a controlled environment, subject to deployment controls described below.

## Prediction and error monitoring

`src/monitoring/prediction_logger.py` appends successful forecast and inference-failure events to `logs/monitoring/predictions.jsonl`; `RETAILIQ_PREDICTION_LOG` can select another path. Events include target date, prediction (if successful), model version, request ID and status. Actual value remains null until it is observed. Request feature values, credentials and exception text are not logged.

`src/monitoring/error_monitor.py` joins actuals only by exact target date, store and family. Duplicate actual keys are rejected. Metrics include MAE, RMSE, WAPE, nonzero-only MAPE, signed bias, zero-actual count and actual coverage with overall/store/family/store-family/month groups. MAPE excludes zero-actual rows; WAPE is undefined when total absolute actual demand is zero.

Production monitoring is `INSUFFICIENT_DATA` until observed production actuals are supplied. Run `python scripts/write_monitoring_report.py`; the expected external input schema is unique `date,store_nbr,family,sales` rows at `data/production/actuals.csv`, optionally with `observed_at`. Run `python scripts/replay_forecast_monitor.py` to recompute the existing held-out historical replay. It is explicitly not production performance.

## Drift and retraining decision

`src/monitoring/drift.py` reports numeric mean movement in baseline standard deviations and standard-deviation ratios, plus categorical total-variation distance. Calendar features remain in the report but are excluded from triggers because unequal windows naturally shift their distribution. `configs/monitoring.yaml` contains sample minimum, thresholds, error-degradation gate and cooldown. These are conservative starting guardrails, not thresholds calibrated against production costs or a live stream. Small windows return `INSUFFICIENT_DATA`; drift/error can return `RETRAIN_REQUIRED`; otherwise status is `MONITOR`. A flag never starts training automatically. `python scripts/check_drift.py reference.csv current.csv` compares supplied feature windows. `python scripts/replay_drift_monitor.py` writes a controlled historical window comparison labeled as simulation.

## Batch retraining and model registry

`python scripts/retrain.py` loads the approved dataset, preserves chronological training/validation windows, fits the selected Random Forest approach with controlled parameters, compares champion-parameter and challenger refits on identical rows, then refits the candidate on train plus validation and writes a new versioned artifact. It never writes to the current artifact and fails if the candidate version already exists. The 2017+ final test is not passed to model fitting or selection.

Because deployed v1.0.2 was trained through its historical validation window, directly evaluating that serialized artifact on the same interval would be contaminated. The retraining comparison is therefore a clearly labeled paired-refit proxy, not a score for the deployed artifact. Acceptance requires at least 1% MAE improvement with no RMSE or R2 regression. Default is candidate-only. Explicit `--promote` can mark a passing version `staging`; it does not replace the serving artifact. A separate reviewed deployment change is required.

`src/serving/dependencies.py` exposes loaded artifact metadata through `ModelRegistry.describe()`. Run `python scripts/register_current_model.py` to seed the lifecycle ledger with the currently served artifact and its measured validation metrics. Candidate and staging events are appended to a local JSONL model-registry log. Experiment results are appended to a local JSONL log only when an actual training/evaluation run records them; no historical results are fabricated or backfilled.

## Testing and limits

Run the complete suite with `python -m pytest -q`. It covers leakage-safe features and splits, serving, logging, actual matching and zero-safe metrics, drift thresholds, registry/experiment records, and candidate promotion gates. The model artifact is local and trusted; do not load untrusted joblib files.

This is a single-service ML implementation with local append-only JSONL logs, not a distributed monitoring platform. Multi-process/multi-host use requires a transactional shared event store. Production rollout still needs authentication and network policy, centralized durable logs, ingestion of actual demand, calibrated operational thresholds and an explicit artifact-release process. No production forecast accuracy or physical-unit inventory performance is claimed.

Generated monitoring logs and reports are local runtime outputs and are not versioned.
