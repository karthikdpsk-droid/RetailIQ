"""Exact target-date actual matching and forecast error summaries."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from .prediction_logger import load_prediction_records

KEYS = ["forecast_date", "store_nbr", "family"]


def join_actuals(predictions: list[dict[str, Any]] | pd.DataFrame,
                 actuals: pd.DataFrame, *, actual_date_column: str = "date",
                 actual_value_column: str = "sales") -> pd.DataFrame:
    """Join only exact target-date/store/family matches; actual keys must be unique."""
    pred = pd.DataFrame(predictions).copy() if not isinstance(predictions, pd.DataFrame) else predictions.copy()
    needed = set(KEYS + ["prediction"])
    if not needed.issubset(pred.columns):
        raise ValueError(f"Prediction data is missing {sorted(needed - set(pred.columns))}")
    actual_required = {actual_date_column, "store_nbr", "family", actual_value_column}
    if not actual_required.issubset(actuals.columns):
        raise ValueError(f"Actual data is missing {sorted(actual_required - set(actuals.columns))}")
    pred["forecast_date"] = pd.to_datetime(pred["forecast_date"], errors="coerce").dt.normalize()
    pred["prediction"] = pd.to_numeric(pred["prediction"], errors="coerce")
    if pred["forecast_date"].isna().any() or not np.isfinite(pred["prediction"].dropna()).all():
        raise ValueError("Prediction target dates and values must be valid and finite")
    if (pred["prediction"].dropna() < 0).any():
        raise ValueError("Forecast demand must be nonnegative")
    if "prediction_id" in pred and pred["prediction_id"].duplicated().any():
        raise ValueError("Prediction ids must be unique")
    # When event times exist, a prediction emitted on/after its target date is not
    # a valid one-day-ahead observation and is excluded from matching.
    if "timestamp" in pred:
        prediction_time = pd.to_datetime(pred["timestamp"], errors="coerce", utc=True)
        target_start = pd.to_datetime(pred["forecast_date"], utc=True)
        pred.loc[prediction_time.isna() | (prediction_time >= target_start), "prediction"] = np.nan
    truth_columns = [actual_date_column, "store_nbr", "family", actual_value_column]
    if "observed_at" in actuals:
        truth_columns.append("observed_at")
    truth = actuals[truth_columns].copy()
    truth[actual_date_column] = pd.to_datetime(truth[actual_date_column], errors="coerce").dt.normalize()
    truth = truth.rename(columns={actual_date_column: "forecast_date", actual_value_column: "actual"})
    if truth[KEYS].isna().any().any() or truth.duplicated(KEYS).any():
        raise ValueError("Actuals must have valid, unique date/store/family keys")
    joined = pred.merge(truth, on=KEYS, how="left", validate="many_to_one")
    if "observed_at" in joined and "timestamp" in joined:
        observed = pd.to_datetime(joined["observed_at"], errors="coerce", utc=True)
        predicted = pd.to_datetime(joined["timestamp"], errors="coerce", utc=True)
        joined.loc[observed.isna() | (observed <= predicted), "actual"] = np.nan
    actual_values = pd.to_numeric(joined["actual"], errors="coerce")
    if (actual_values.dropna() < 0).any() or not np.isfinite(actual_values.dropna()).all():
        raise ValueError("Actual demand must be finite and nonnegative")
    joined["actual"] = actual_values
    return joined


def _metric_row(frame: pd.DataFrame, dimensions: dict[str, Any]) -> dict[str, Any]:
    valid = frame["actual"].notna() & frame["prediction"].notna()
    scored = frame.loc[valid].copy()
    if len(scored):
        error = scored["prediction"].astype(float) - scored["actual"].astype(float)
        abs_error = error.abs()
        denominator = scored["actual"].abs().sum()
        nonzero = scored["actual"].abs() > 0
        mape = float((abs_error[nonzero] / scored.loc[nonzero, "actual"].abs()).mean() * 100) if nonzero.any() else None
        result = {
            "n_predictions": int(len(frame)), "n_with_actual": int(len(scored)),
            "actual_coverage": float(len(scored) / len(frame)) if len(frame) else None,
            "MAE": float(abs_error.mean()), "RMSE": float(np.sqrt(np.square(error).mean())),
            "WAPE": float(abs_error.sum() / denominator * 100) if denominator > 0 else None,
            "MAPE_nonzero_actual_pct": mape, "bias_mean_error": float(error.mean()),
            "n_zero_actual": int((scored["actual"] == 0).sum()),
        }
    else:
        result = {"n_predictions": int(len(frame)), "n_with_actual": 0,
                  "actual_coverage": 0.0 if len(frame) else None, "MAE": None, "RMSE": None,
                  "WAPE": None, "MAPE_nonzero_actual_pct": None, "bias_mean_error": None,
                  "n_zero_actual": 0}
    return {**dimensions, **result}


def aggregate_errors(joined: pd.DataFrame) -> pd.DataFrame:
    rows = [_metric_row(joined, {"aggregation": "overall", "group": "all"})]
    groups = [(["store_nbr"], "store"), (["family"], "family"),
              (["store_nbr", "family"], "store_family")]
    valid_dates = pd.to_datetime(joined["forecast_date"], errors="coerce")
    with_month = joined.assign(time_period=valid_dates.dt.to_period("M").astype(str))
    for columns, name in groups:
        for key, part in joined.groupby(columns, dropna=False, observed=True):
            values = key if isinstance(key, tuple) else (key,)
            group = "/".join(str(v) for v in values)
            rows.append(_metric_row(part, {"aggregation": name, "group": group}))
    for key, part in with_month.groupby(["time_period"], dropna=False, observed=True):
        rows.append(_metric_row(part, {"aggregation": "month", "group": str(key)}))
    return pd.DataFrame(rows)


def monitor_actuals(prediction_path: str | Path, actuals: pd.DataFrame,
                    report_path: str | Path | None = None,
                    *, source_label: str = "production") -> tuple[pd.DataFrame, dict[str, Any]]:
    records = [r for r in load_prediction_records(prediction_path)
               if r.get("endpoint") == "/forecast" and r.get("status") == "success"]
    if not records:
        empty = pd.DataFrame(columns=["aggregation", "group", "n_predictions", "n_with_actual",
                                      "actual_coverage", "MAE", "RMSE", "WAPE",
                                      "MAPE_nonzero_actual_pct", "bias_mean_error", "n_zero_actual"])
        report = {"report_type": f"{source_label}_forecast_monitoring",
                  "generated_at": pd.Timestamp.now(tz="UTC").isoformat(), "n_predictions": 0,
                  "n_with_actual": 0, "actual_coverage": None, "MAE": None, "RMSE": None,
                  "WAPE_pct": None, "bias_mean_error": None, "zero_actual_rows": 0,
                  "note": "No successful forecast records are available; no accuracy is claimed."}
        if report_path:
            target = Path(report_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
            empty.to_csv(target.with_name(target.stem + "_breakdowns.csv"), index=False)
        return empty, report
    joined = join_actuals(records, actuals)
    metrics = aggregate_errors(joined)
    overall = metrics.iloc[0].to_dict() if len(metrics) else _metric_row(joined, {"aggregation": "overall", "group": "all"})
    report = {"report_type": f"{source_label}_forecast_monitoring", "generated_at": pd.Timestamp.now(tz="UTC").isoformat(),
              "n_predictions": overall["n_predictions"], "n_with_actual": overall["n_with_actual"],
              "actual_coverage": overall["actual_coverage"], "MAE": overall["MAE"], "RMSE": overall["RMSE"],
              "WAPE_pct": overall["WAPE"], "bias_mean_error": overall["bias_mean_error"],
              "zero_actual_rows": overall["n_zero_actual"],
              "note": "Metrics use only exact forecast target-date/store/family joins. MAPE excludes zero-actual rows; WAPE is unavailable when total absolute actual demand is zero."}
    if report_path:
        target = Path(report_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
        metrics.to_csv(target.with_name(target.stem + "_breakdowns.csv"), index=False)
    return metrics, report


def monitor_historical_replay(backtest_path: str | Path,
                              report_path: str | Path | None = None) -> tuple[pd.DataFrame, dict[str, Any]]:
    """Evaluate saved historical backtest forecasts; label output as replay, never production."""
    replay = pd.read_csv(backtest_path, usecols=["forecast_date", "store_nbr", "family",
                                                 "predicted_demand", "actual"])
    prediction = replay.rename(columns={"predicted_demand": "prediction"}).drop(columns=["actual"])
    actual = replay.rename(columns={"actual": "sales", "forecast_date": "date"})[
        ["date", "store_nbr", "family", "sales"]]
    joined = join_actuals(prediction, actual)
    # The backtest file is already key-unique by construction, and join_actuals
    # independently enforces one actual per target key.
    metrics = aggregate_errors(joined)
    overall = metrics.iloc[0].to_dict()
    report = {"report_type": "historical_holdout_replay", "generated_at": pd.Timestamp.now(tz="UTC").isoformat(),
              "source": str(backtest_path), "n_predictions": overall["n_predictions"],
              "n_with_actual": overall["n_with_actual"], "actual_coverage": overall["actual_coverage"],
              "MAE": overall["MAE"], "RMSE": overall["RMSE"], "WAPE_pct": overall["WAPE"],
              "bias_mean_error": overall["bias_mean_error"], "zero_actual_rows": overall["n_zero_actual"],
              "note": "Historical held-out evaluation replay. This is not ongoing production monitoring or a production performance claim."}
    if report_path:
        target = Path(report_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
        metrics.to_csv(target.with_name(target.stem + "_breakdowns.csv"), index=False)
    return metrics, report
