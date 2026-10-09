"""Thin orchestration adapters around existing forecasting/inventory logic."""
from __future__ import annotations

import logging
import math
import time

import pandas as pd

from src.inventory.optimization import recommend_inventory
from .dependencies import ModelBundle
from .schemas import ForecastRequest, InventoryOptimizeRequest
from src.monitoring.prediction_logger import log_prediction

logger = logging.getLogger(__name__)


class InferenceFailure(RuntimeError):
    pass


def _log_prediction_safely(**fields) -> None:
    try:
        log_prediction(**fields)
    except Exception as exc:  # logging failure must not change a valid serving response
        logger.error("prediction_log_write_failed", extra={"error_category": type(exc).__name__})


def forecast(bundle: ModelBundle, request: ForecastRequest, request_id: str) -> dict:
    started = time.perf_counter()
    features = request.model_dump(exclude={"forecast_date", "prediction_cutoff"})
    frame = pd.DataFrame([{name: features[name] for name in bundle.features}], columns=bundle.features)
    try:
        values = bundle.pipeline.predict(frame)
        if len(values) != 1:
            raise ValueError("unexpected prediction count")
        prediction = float(values[0])
        if not math.isfinite(prediction):
            raise ValueError("non-finite model prediction")
        prediction = max(0.0, prediction)
    except Exception as exc:
        _log_prediction_safely(endpoint="/forecast", store_nbr=request.store_nbr,
                                family=request.family, forecast_date=request.forecast_date,
                                prediction=None, model_version=bundle.api_version,
                                request_id=request_id, status="failure",
                                error_category=type(exc).__name__)
        logger.exception("forecast_inference_failed", extra={
            "request_id": request_id, "store_nbr": request.store_nbr,
            "family": request.family, "model_version": bundle.api_version,
            "error_category": type(exc).__name__,
        })
        raise InferenceFailure("Forecast inference failed") from exc
    elapsed_ms = (time.perf_counter() - started) * 1000
    logger.info("forecast_succeeded", extra={
        "request_id": request_id, "store_nbr": request.store_nbr,
        "family": request.family, "model_version": bundle.api_version,
        "inference_ms": round(elapsed_ms, 3),
    })
    _log_prediction_safely(endpoint="/forecast", store_nbr=request.store_nbr,
                            family=request.family, forecast_date=request.forecast_date,
                            prediction=prediction, model_version=bundle.api_version,
                            request_id=request_id, status="success", actual_value=None)
    return {
        "store_nbr": request.store_nbr, "family": request.family,
        "forecast_date": request.forecast_date, "forecast_demand": prediction,
        "forecast_horizon_days": 1, "forecast_type": "point_forecast",
        "model_version": bundle.api_version,
    }


def optimize_inventory(bundle: ModelBundle, request: InventoryOptimizeRequest) -> dict:
    payload = request.model_dump(mode="json", exclude={"model_version"}, exclude_none=True)
    payload["model_version"] = bundle.version
    return recommend_inventory(payload)
