"""Explainable numeric/categorical drift summaries and a safe retraining flag."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class DriftDecision:
    status: str
    reasons: tuple[str, ...]
    metrics: dict[str, Any]


def _numeric_shift(reference: pd.Series, current: pd.Series) -> dict[str, float | None]:
    ref = pd.to_numeric(reference, errors="coerce").replace([np.inf, -np.inf], np.nan).dropna()
    cur = pd.to_numeric(current, errors="coerce").replace([np.inf, -np.inf], np.nan).dropna()
    if not len(ref) or not len(cur):
        return {"mean_shift_sd": None, "std_ratio": None}
    ref_mean, cur_mean = float(ref.mean()), float(cur.mean())
    ref_std, cur_std = float(ref.std(ddof=1)), float(cur.std(ddof=1))
    shift = abs(cur_mean - ref_mean) / ref_std if ref_std > 0 else (0.0 if cur_mean == ref_mean else 1e12)
    ratio = cur_std / ref_std if ref_std > 0 else (1.0 if cur_std == 0 else 1e12)
    return {"mean_shift_sd": shift, "std_ratio": ratio}


def _categorical_tv(reference: pd.Series, current: pd.Series) -> float | None:
    ref, cur = reference.fillna("<MISSING>").astype(str), current.fillna("<MISSING>").astype(str)
    if ref.empty or cur.empty:
        return None
    categories = set(ref.unique()) | set(cur.unique())
    p = ref.value_counts(normalize=True).reindex(categories, fill_value=0.0)
    q = cur.value_counts(normalize=True).reindex(categories, fill_value=0.0)
    return float(0.5 * (p - q).abs().sum())


def compare_feature_drift(reference: pd.DataFrame, current: pd.DataFrame,
                          features: list[str] | tuple[str, ...] | None = None) -> dict[str, Any]:
    columns = list(features or [column for column in reference.columns if column in current.columns])
    missing = set(columns) - set(reference.columns) | (set(columns) - set(current.columns))
    if missing:
        raise ValueError(f"Drift inputs are missing features: {sorted(missing)}")
    output = {}
    for column in columns:
        if pd.api.types.is_numeric_dtype(reference[column]) and pd.api.types.is_numeric_dtype(current[column]):
            output[column] = {"kind": "numeric", **_numeric_shift(reference[column], current[column])}
        else:
            output[column] = {"kind": "categorical", "total_variation": _categorical_tv(reference[column], current[column])}
    return output


def decide_retraining(*, reference: pd.DataFrame, current: pd.DataFrame,
                      prediction_reference: pd.Series | None = None,
                      prediction_current: pd.Series | None = None,
                      actual_count: int = 0, config: dict[str, Any] | None = None,
                      error_degradation: float | None = None,
                      last_retraining_at: datetime | None = None,
                      now: datetime | None = None) -> DriftDecision:
    """Return a flag only; it never starts training or changes the serving model."""
    cfg = config or {}
    minimum = int(cfg.get("minimum_sample_size", 100))
    metrics = compare_feature_drift(reference, current)
    reasons = []
    if len(reference) < minimum or len(current) < minimum:
        return DriftDecision("INSUFFICIENT_DATA", ("feature window below configured sample minimum",), metrics)
    numeric_threshold = float(cfg.get("numeric_mean_shift_sd_threshold", 1.0))
    std_ratio_min = float(cfg.get("numeric_std_ratio_min", 0.5))
    std_ratio_max = float(cfg.get("numeric_std_ratio_max", 2.0))
    categorical_threshold = float(cfg.get("categorical_total_variation_threshold", 0.2))
    excluded_features = set(cfg.get("excluded_drift_features", []))
    for feature, result in metrics.items():
        # Calendar fields are deterministic seasonality controls; window-to-window
        # shifts are expected and should not independently raise a retraining flag.
        if feature in excluded_features:
            continue
        shift = result.get("mean_shift_sd")
        tv = result.get("total_variation")
        if shift is not None and shift > numeric_threshold:
            reasons.append(f"{feature}: mean shift exceeds configured SD threshold")
        ratio = result.get("std_ratio")
        if ratio is not None and (ratio < std_ratio_min or ratio > std_ratio_max):
            reasons.append(f"{feature}: standard-deviation ratio is outside configured range")
        if tv is not None and tv > categorical_threshold:
            reasons.append(f"{feature}: categorical variation exceeds configured threshold")
    pred_threshold = float(cfg.get("prediction_mean_shift_sd_threshold", 1.0))
    if prediction_reference is not None and prediction_current is not None:
        prediction_shift = _numeric_shift(prediction_reference, prediction_current)
        metrics["prediction"] = prediction_shift
        if prediction_shift["mean_shift_sd"] is not None and prediction_shift["mean_shift_sd"] > pred_threshold:
            reasons.append("prediction mean shift exceeds configured SD threshold")
        ratio = prediction_shift.get("std_ratio")
        if ratio is not None and (ratio < std_ratio_min or ratio > std_ratio_max):
            reasons.append("prediction standard-deviation ratio is outside configured range")
    minimum_actuals = int(cfg.get("minimum_actual_count_for_error_flag", 100))
    if error_degradation is not None:
        metrics["error_degradation_fraction"] = float(error_degradation)
        if actual_count >= minimum_actuals and error_degradation > float(cfg.get("mae_degradation_fraction_threshold", 0.2)):
            reasons.append("observed MAE degradation exceeds configured threshold")
    if reasons and last_retraining_at is not None:
        cooldown_days = int(cfg.get("retraining_cooldown_days", 0))
        current_time = now or datetime.now(timezone.utc)
        previous = last_retraining_at
        if previous.tzinfo is None:
            previous = previous.replace(tzinfo=timezone.utc)
        if current_time.tzinfo is None:
            current_time = current_time.replace(tzinfo=timezone.utc)
        if (current_time - previous).total_seconds() < cooldown_days * 86400:
            reasons = ["retraining cooldown is active; continue monitoring"]
    status = "RETRAIN_REQUIRED" if reasons and not reasons[0].startswith("retraining cooldown") else "MONITOR"
    return DriftDecision(status, tuple(reasons), metrics)
