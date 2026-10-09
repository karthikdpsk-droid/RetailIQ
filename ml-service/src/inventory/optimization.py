"""Reusable, assumption-explicit inventory policy calculations for RetailIQ.

The module consumes next-day demand forecasts and historical sales. It does not
read or infer live on-hand stock; inventory is optional caller-supplied input.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from math import isfinite, sqrt
from pathlib import Path
from statistics import NormalDist
from typing import Any, Mapping, Sequence

import yaml

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "configs" / "inventory.yaml"
DEFAULTS = {
    "default_lead_time_days": 7,
    "service_level": 0.95,
    "review_period_days": 1,
    "minimum_order_quantity": None,
    "maximum_stock_level": None,
    "maximum_supported_lead_time_days": 365,
    "variability_window_days": 28,
    "minimum_history_observations": 2,
    "variability_method": "sample_standard_deviation",
}
RESULT_FIELDS = (
    "store_nbr", "family", "forecast_date", "forecast_demand", "demand_variability",
    "lead_time", "review_period", "service_level", "safety_stock", "reorder_point",
    "target_stock", "current_inventory", "on_order_inventory",
    "backorder_quantity", "inventory_position", "recommended_order_quantity",
    "inventory_position_basis", "stock_status", "model_version", "calculation_timestamp",
)


def load_config(config: Mapping[str, Any] | str | Path | None = None) -> dict[str, Any]:
    """Load YAML configuration or merge caller overrides with safe defaults."""
    if config is None:
        path = DEFAULT_CONFIG_PATH
        loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    elif isinstance(config, (str, Path)):
        loaded = yaml.safe_load(Path(config).read_text(encoding="utf-8")) or {}
    else:
        loaded = dict(config)
    unknown = set(loaded) - set(DEFAULTS)
    if unknown:
        raise ValueError(f"Unknown inventory configuration keys: {sorted(unknown)}")
    result = {**DEFAULTS, **loaded}
    _validate_config(result)
    return result


def _number(value: Any, name: str, *, optional: bool = False, minimum: float = 0.0) -> float | None:
    if value is None:
        if optional:
            return None
        raise ValueError(f"{name} is required")
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric") from exc
    if not isfinite(number) or number < minimum:
        raise ValueError(f"{name} must be finite and at least {minimum}")
    return number


def _validate_config(config: Mapping[str, Any]) -> None:
    _number(config["default_lead_time_days"], "default_lead_time_days")
    level = _number(config["service_level"], "service_level")
    if not 0 < level < 1:
        raise ValueError("service_level must be strictly between 0 and 1")
    _number(config["review_period_days"], "review_period_days")
    _number(config["maximum_supported_lead_time_days"], "maximum_supported_lead_time_days", minimum=1)
    _number(config["variability_window_days"], "variability_window_days", minimum=1)
    _number(config["minimum_history_observations"], "minimum_history_observations", minimum=2)
    for key in ("minimum_order_quantity", "maximum_stock_level"):
        _number(config[key], key, optional=True)
    if config["variability_method"] != "sample_standard_deviation":
        raise ValueError("Only sample_standard_deviation variability is currently supported")


def estimate_demand_variability(
    historical_demand: Sequence[Any] | None,
    *,
    cutoff_date: date | datetime | str | None = None,
    window_days: int = 28,
    minimum_observations: int = 2,
) -> float | None:
    """Sample SD of observed daily demand in the trailing calendar window.

    Entries may be numeric (caller guarantees they are historical), mappings
    with ``date`` and ``sales``/``demand``, or ``(date, demand)`` pairs. When a
    cutoff is provided, dated values after it are discarded. Missing calendar
    dates are skipped, never silently treated as zero. Explicit zero-demand
    records are retained. Returns None when the sample is insufficient.
    """
    if historical_demand is None:
        return None
    cutoff = _as_date(cutoff_date) if cutoff_date is not None else None
    if window_days < 1 or minimum_observations < 2:
        raise ValueError("window_days must be positive and minimum_observations at least two")
    observations: list[float] = []
    for item in historical_demand:
        observed_date = None
        if isinstance(item, Mapping):
            observed_date = _as_date(item.get("date")) if item.get("date") is not None else None
            raw = item.get("sales", item.get("demand"))
        elif isinstance(item, (tuple, list)) and len(item) == 2:
            observed_date, raw = _as_date(item[0]), item[1]
        else:
            raw = item
        if observed_date is not None and cutoff is None:
            raise ValueError("cutoff_date is required when historical demand has dates")
        if observed_date is not None and cutoff is not None:
            if observed_date > cutoff or observed_date < cutoff - timedelta(days=window_days - 1):
                continue
        value = _number(raw, "historical demand")
        observations.append(value)
    if len(observations) < minimum_observations:
        return None
    mean = sum(observations) / len(observations)
    variance = sum((value - mean) ** 2 for value in observations) / (len(observations) - 1)
    result = sqrt(variance)
    if not isfinite(result):
        raise OverflowError("Demand variability is outside the supported numeric range")
    return result


def stock_parameters(
    forecast_demand: float,
    demand_variability: float,
    lead_time_days: float,
    service_level: float,
    review_period_days: float = 1,
) -> dict[str, float]:
    """Return daily-forecast policy quantities in demand-equivalent units.

    z = NormalDist().inv_cdf(service_level); SS = z*sigma*sqrt(L).
    ROP = forecast_daily*L + SS. Target = forecast_daily*(L+R) +
    z*sigma*sqrt(L+R). A flat daily forecast over the protection period and
    independent daily errors are assumptions; these are planning estimates,
    not exact optima.
    """
    forecast = _number(forecast_demand, "forecast_demand")
    sigma = _number(demand_variability, "demand_variability")
    lead = _number(lead_time_days, "lead_time_days")
    review = _number(review_period_days, "review_period_days")
    level = _number(service_level, "service_level")
    if not 0 < level < 1:
        raise ValueError("service_level must be strictly between 0 and 1")
    protection = lead + review
    z_score = NormalDist().inv_cdf(level)
    safety = z_score * sigma * sqrt(lead)
    reorder = forecast * lead + safety
    target = forecast * protection + z_score * sigma * sqrt(protection)
    values = (safety, reorder, target)
    if not all(isfinite(v) for v in values):
        raise OverflowError("Inventory calculation exceeded the finite numeric range")
    return {"safety_stock": max(0.0, safety), "reorder_point": max(0.0, reorder), "target_stock": max(0.0, target)}


def _as_date(value: date | datetime | str | Any) -> date:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value)[:10])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Invalid date: {value!r}") from exc


def recommend_inventory(
    item: Mapping[str, Any],
    config: Mapping[str, Any] | str | Path | None = None,
    *,
    calculation_time: datetime | None = None,
) -> dict[str, Any]:
    """Calculate a structured inventory recommendation for one store/family.

    Required identity keys are store_nbr/family. Supply forecast_demand and
    as-of historical_demand. Optional current_inventory is on-hand stock;
    on_order_inventory and backorder_quantity are distinct optional components.
    Missing inventory never receives an invented value.
    """
    cfg = load_config(config)
    result = {field: None for field in RESULT_FIELDS}
    result.update({"store_nbr": item.get("store_nbr"), "family": item.get("family"),
                   "forecast_date": item.get("forecast_date"),
                   "model_version": item.get("model_version")})
    now = calculation_time or datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    result["calculation_timestamp"] = now.astimezone(timezone.utc).isoformat()

    forecast_raw = item.get("forecast_demand")
    if forecast_raw is None:
        result["stock_status"] = "UNKNOWN / FORECAST_REQUIRED"
        return result
    forecast = _number(forecast_raw, "forecast_demand")
    lead_raw = item.get("lead_time_days", cfg["default_lead_time_days"])
    if lead_raw is None:
        lead_raw = cfg["default_lead_time_days"]
    lead = _number(lead_raw, "lead_time_days")
    if lead > cfg["maximum_supported_lead_time_days"]:
        raise ValueError("lead_time_days exceeds maximum_supported_lead_time_days")
    service = _number(item.get("service_level", cfg["service_level"]), "service_level")
    if not 0 < service < 1:
        raise ValueError("service_level must be strictly between 0 and 1")
    review = _number(item.get("review_period_days", cfg["review_period_days"]), "review_period_days")
    result.update({"forecast_demand": forecast, "lead_time": lead,
                   "review_period": review, "service_level": service})

    cutoff = item.get("prediction_cutoff")
    variability = estimate_demand_variability(
        item.get("historical_demand"), cutoff_date=cutoff,
        window_days=int(cfg["variability_window_days"]),
        minimum_observations=int(cfg["minimum_history_observations"]),
    )
    result["demand_variability"] = variability
    if variability is None:
        result["stock_status"] = "UNKNOWN / INSUFFICIENT_HISTORY"
        return result
    params = stock_parameters(forecast, variability, lead, service, review)
    maximum = _number(cfg["maximum_stock_level"], "maximum_stock_level", optional=True)
    if maximum is not None:
        if maximum < params["reorder_point"]:
            result.update(params)
            result["target_stock"] = maximum
            result["stock_status"] = "UNKNOWN / CONSTRAINT_CONFLICT"
            return result
        params["target_stock"] = min(params["target_stock"], maximum)
    result.update(params)

    on_hand_raw = item.get("current_inventory", item.get("on_hand_inventory"))
    on_order_raw = item.get("on_order_inventory")
    backorder_raw = item.get("backorder_quantity")
    on_hand = _number(on_hand_raw, "current_inventory", optional=True)
    on_order = _number(on_order_raw, "on_order_inventory", optional=True)
    backorders = _number(backorder_raw, "backorder_quantity", optional=True)
    result["current_inventory"] = on_hand
    result["on_order_inventory"] = on_order
    result["backorder_quantity"] = backorders
    if on_hand is None:
        result["stock_status"] = "UNKNOWN / INVENTORY_DATA_REQUIRED"
        return result
    # Unknown pipeline stock and backorders are zero only for the arithmetic;
    # their output fields remain null so the caller can see they were absent.
    position = on_hand + (on_order or 0.0) - (backorders or 0.0)
    if not isfinite(position):
        raise OverflowError("Inventory position exceeded the finite numeric range")
    result["inventory_position"] = position
    missing_components = [name for name, value in (("on_order_inventory", on_order), ("backorder_quantity", backorders)) if value is None]
    result["inventory_position_basis"] = "on_hand + (on_order or 0) - (backorders or 0)"
    if missing_components:
        result["inventory_position_basis"] += "; assumed zero: " + ", ".join(missing_components)
    shortage = max(0.0, params["target_stock"] - position)
    minimum = _number(cfg["minimum_order_quantity"], "minimum_order_quantity", optional=True)
    order_qty = max(shortage, minimum or 0.0) if shortage > 0 else 0.0
    if maximum is not None:
        capacity = max(0.0, maximum - position)
        if order_qty > capacity and minimum is not None and capacity < minimum:
            result["stock_status"] = "UNKNOWN / CONSTRAINT_CONFLICT"
            return result
        order_qty = min(order_qty, capacity)
    result["recommended_order_quantity"] = max(0.0, order_qty)
    if position < params["safety_stock"]:
        status = "CRITICAL"
    elif position <= params["reorder_point"] and order_qty > 0:
        status = "REORDER"
    elif position > params["target_stock"]:
        status = "ABOVE_TARGET"
    else:
        status = "ADEQUATE"
    result["stock_status"] = status
    return result


def recommend_batch(items: Sequence[Mapping[str, Any]], config: Mapping[str, Any] | str | Path | None = None) -> list[dict[str, Any]]:
    """Structured batch interface suitable for a future API handler."""
    return [recommend_inventory(item, config) for item in items]


def recommend_from_forecasts(
    forecast_rows: Sequence[Mapping[str, Any]],
    historical_demand_by_key: Mapping[tuple[Any, Any], Sequence[Any]],
    inventory_by_key: Mapping[tuple[Any, Any], Mapping[str, Any]] | None = None,
    *,
    model_version: str | None = None,
    config: Mapping[str, Any] | str | Path | None = None,
) -> list[dict[str, Any]]:
    """Join finalized forecast rows to as-of history and optional stock input.

    History must contain dated records; it is filtered to dates no later than
    the day before each forecast date. Without ``inventory_by_key``, output is
    the explicit inventory-required mode.
    """
    inventory_by_key = inventory_by_key or {}
    items = []
    for row in forecast_rows:
        key = (row["store_nbr"], row["family"])
        forecast_day = _as_date(row.get("forecast_date", row.get("date")))
        cutoff = forecast_day - timedelta(days=1)
        item = {
            "store_nbr": key[0], "family": key[1],
            "forecast_date": forecast_day.isoformat(), "prediction_cutoff": cutoff.isoformat(),
            "forecast_demand": row.get("predicted_demand", row.get("forecast_demand")),
            "historical_demand": historical_demand_by_key.get(key, ()),
            "model_version": model_version,
        }
        item.update(inventory_by_key.get(key, {}))
        items.append(item)
    return recommend_batch(items, config)


def load_forecast_rows(path: str | Path) -> list[dict[str, Any]]:
    """Load finalized forecast output without attaching synthetic inventory."""
    import pandas as pd
    frame = pd.read_csv(path)
    required = {"store_nbr", "family", "forecast_date", "predicted_demand"}
    if required - set(frame.columns):
        raise ValueError(f"Forecast output missing columns: {sorted(required - set(frame.columns))}")
    return [{"store_nbr": row.store_nbr, "family": row.family,
             "forecast_date": row.forecast_date, "forecast_demand": row.predicted_demand}
            for row in frame.itertuples(index=False)]
