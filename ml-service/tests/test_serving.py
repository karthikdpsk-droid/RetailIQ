from datetime import date, timedelta
import json
import importlib
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pytest
from fastapi.testclient import TestClient

from src.forecasting import FEATURES
from src.serving.app import app, create_app
from src.serving.dependencies import ModelBundle, ModelRegistry, get_model_bundle


def forecast_payload(**changes):
    target = date(2017, 1, 1)
    payload = {
        "forecast_date": target.isoformat(), "prediction_cutoff": (target - timedelta(days=1)).isoformat(),
        "store_nbr": 1, "family": "AUTOMOTIVE",
        "onpromotion": 0, "city": "Quito", "state": "Pichincha", "type": "D",
        "cluster": 13, "dcoilwtico": 53.75, "is_holiday_event": 0,
        "year": target.year, "month": target.month, "day": target.day,
        "day_of_week": target.weekday(), "week_of_year": target.isocalendar().week,
        "quarter": 1, "is_weekend": 1, "has_promotion": 0,
        "sales_lag_1": 20.0, "sales_lag_7": 18.0, "sales_lag_14": 15.0,
        "sales_lag_28": 17.0, "sales_rolling_mean_7": 19.0,
        "sales_rolling_mean_14": 18.0, "sales_rolling_mean_28": 16.0,
        "sales_rolling_std_7": 2.5,
    }
    payload.update(changes)
    return payload


def inventory_payload(**changes):
    cutoff = date(2016, 12, 31)
    payload = {
        "store_nbr": 1, "family": "AUTOMOTIVE", "forecast_date": "2017-01-01",
        "forecast_demand": 123.45, "prediction_cutoff": cutoff.isoformat(),
        "historical_demand": [
            {"date": (cutoff - timedelta(days=i)).isoformat(), "sales": 100 + i % 7}
            for i in range(27, -1, -1)
        ],
    }
    payload.update(changes)
    return payload


def test_health_endpoint_reports_loaded_model():
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok", "service": "retailiq-ml-service",
        "model_version": "demand_forecast_v1.0.2",
    }
    assert response.headers["x-request-id"]


def test_valid_forecast_uses_model_returns_finite_version_and_metadata():
    with TestClient(app) as client:
        response = client.post("/forecast", json=forecast_payload(), headers={"X-Request-ID": "test-forecast-1"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["store_nbr"] == 1 and body["family"] == "AUTOMOTIVE"
    assert np.isfinite(body["forecast_demand"]) and body["forecast_demand"] >= 0
    assert body["forecast_horizon_days"] == 1
    assert body["forecast_type"] == "point_forecast"
    assert body["model_version"] == "demand_forecast_v1.0.2"
    assert response.headers["x-request-id"] == "test-forecast-1"
    assert "lower_bound" not in body and "upper_bound" not in body


def test_forecast_prediction_is_logged_without_changing_api_response(tmp_path, monkeypatch):
    from src.monitoring.prediction_logger import load_prediction_records
    monkeypatch.setenv("RETAILIQ_PREDICTION_LOG", str(tmp_path / "predictions.jsonl"))
    with TestClient(app) as client:
        response = client.post("/forecast", json=forecast_payload(), headers={"X-Request-ID": "logged-1"})
    assert response.status_code == 200
    assert set(response.json()) == {"store_nbr", "family", "forecast_date", "forecast_demand",
                                    "forecast_horizon_days", "forecast_type", "model_version"}
    records = load_prediction_records(tmp_path / "predictions.jsonl")
    assert len(records) == 1
    assert records[0]["prediction"] == response.json()["forecast_demand"]
    assert records[0]["actual_value"] is None
    assert records[0]["request_id"] == "logged-1"


def test_unseen_categorical_values_follow_model_encoder_policy():
    with TestClient(app) as client:
        response = client.post("/forecast", json=forecast_payload(family="UNSEEN FAMILY", city="New City"))
    assert response.status_code == 200
    assert np.isfinite(response.json()["forecast_demand"])


def test_invalid_forecast_missing_or_extra_feature_is_structured_422():
    missing = forecast_payload()
    missing.pop("sales_lag_1")
    with TestClient(app) as client:
        response = client.post("/forecast", json=missing)
        extra = client.post("/forecast", json={**forecast_payload(), "future_sales": 99})
    assert response.status_code == 422
    assert response.json()["status"] == "error"
    assert response.json()["message"] == "Invalid request"
    assert extra.status_code == 422
    assert extra.json()["details"]


def test_nonfinite_and_invalid_calendar_forecast_inputs_rejected():
    with TestClient(app) as client:
        nonfinite = client.post("/forecast", content=json.dumps(forecast_payload(dcoilwtico=float("nan")), allow_nan=True), headers={"content-type": "application/json"})
        promotion = client.post("/forecast", json=forecast_payload(onpromotion=-1))
        bad_calendar = client.post("/forecast", json=forecast_payload(month=2))
    assert nonfinite.status_code == 422
    assert promotion.status_code == 422
    assert bad_calendar.status_code == 422


def test_valid_inventory_with_on_hand_uses_existing_policy():
    with TestClient(app) as client:
        response = client.post("/inventory/optimize", json=inventory_payload(current_inventory=0))
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["stock_status"] in {"CRITICAL", "REORDER"}
    assert body["inventory_position"] == 0
    assert body["recommended_order_quantity"] > 0
    assert body["lead_time"] == 7
    assert body["service_level"] == 0.95
    assert body["model_version"] == "1.0.2"
    assert body["forecast_date"] == "2017-01-01"


def test_inventory_without_on_hand_keeps_mode_a_unknown_and_quantity_unavailable():
    with TestClient(app) as client:
        response = client.post("/inventory/optimize", json=inventory_payload())
    assert response.status_code == 200
    body = response.json()
    assert body["stock_status"] == "UNKNOWN / INVENTORY_DATA_REQUIRED"
    assert body["current_inventory"] is None
    assert body["inventory_position"] is None
    assert body["recommended_order_quantity"] is None
    assert body["on_order_inventory"] is None
    assert body["backorder_quantity"] is None


def test_inventory_missing_forecast_fails_closed():
    with TestClient(app) as client:
        response = client.post("/inventory/optimize", json=inventory_payload(forecast_demand=None, current_inventory=20))
    assert response.status_code == 200
    body = response.json()
    assert body["stock_status"] == "UNKNOWN / FORECAST_REQUIRED"
    assert body["recommended_order_quantity"] is None


def test_inventory_alias_and_optional_position_components_are_disclosed():
    with TestClient(app) as client:
        response = client.post("/inventory/optimize", json=inventory_payload(on_hand_inventory=5))
    assert response.status_code == 200
    body = response.json()
    assert body["current_inventory"] == 5
    assert body["inventory_position"] == 5
    assert "assumed zero" in body["inventory_position_basis"]


def test_invalid_and_negative_inventory_rejected():
    with TestClient(app) as client:
        negative = client.post("/inventory/optimize", json=inventory_payload(current_inventory=-2))
        nonfinite = client.post("/inventory/optimize", content=json.dumps(inventory_payload(forecast_demand=float("inf")), allow_nan=True), headers={"content-type": "application/json"})
        invalid_cutoff = client.post("/inventory/optimize", json=inventory_payload(prediction_cutoff="2017-01-02"))
    assert negative.status_code == 422
    assert nonfinite.status_code == 422
    assert invalid_cutoff.status_code == 422


def test_model_load_failure_is_degraded_and_does_not_expose_path(tmp_path):
    broken_app = create_app(ModelRegistry(tmp_path / "not-a-model.joblib"))
    with TestClient(broken_app) as client:
        response = client.get("/health")
        forecast = client.post("/forecast", json=forecast_payload())
    assert response.status_code == 503
    assert response.json() == {"status": "degraded", "service": "retailiq-ml-service", "model_version": None}
    assert forecast.status_code == 503
    assert str(tmp_path) not in forecast.text


def test_registry_loads_artifact_once_for_repeated_requests():
    calls = []
    class NeverPredict:
        def predict(self, _frame):
            return np.array([1.0])
    def loader(_path):
        calls.append(1)
        time.sleep(0.03)
        return {"version": "1.0.2", "features": list(FEATURES), "pipeline": NeverPredict()}
    registry = ModelRegistry("unused.joblib", loader=loader)
    with ThreadPoolExecutor(max_workers=8) as pool:
        bundles = list(pool.map(lambda _i: registry.get(), range(8)))
    assert all(bundle is bundles[0] for bundle in bundles)
    assert len(calls) == 1


def test_unexpected_inference_failure_returns_safe_500(tmp_path):
    class BrokenPipeline:
        def predict(self, _frame):
            raise RuntimeError("secret path C:/private/model.joblib")
    bundle = ModelBundle(BrokenPipeline(), "1.0.2", tuple(FEATURES))
    app.dependency_overrides[get_model_bundle] = lambda: bundle
    try:
        with TestClient(app) as client:
            response = client.post("/forecast", json=forecast_payload())
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 500
    assert response.json()["message"] == "Forecast inference failed"
    assert "private" not in response.text and "Traceback" not in response.text
    from src.monitoring.prediction_logger import load_prediction_records
    failures = load_prediction_records(tmp_path / "predictions.jsonl")
    assert len(failures) == 1 and failures[0]["status"] == "failure"
    assert failures[0]["prediction"] is None


def test_loaded_registry_describes_served_model_without_contract_change():
    descriptor = ModelRegistry().describe()
    assert descriptor["model_version"] == "1.0.2"
    assert descriptor["artifact_path"].endswith("demand_forecast_v1.0.2.joblib")
    assert descriptor["status"] == "champion"
    assert descriptor["validation_metrics"]["MAE"] == pytest.approx(61.4526, abs=1e-3)


def test_unexpected_inventory_error_returns_safe_500(monkeypatch):
    app_module = importlib.import_module("src.serving.app")
    def fail(*_args, **_kwargs):
        raise RuntimeError("sensitive internal detail")
    monkeypatch.setattr(app_module, "optimize_inventory", fail)
    with TestClient(app) as client:
        response = client.post("/inventory/optimize", json=inventory_payload(current_inventory=20))
    assert response.status_code == 500
    assert response.json()["message"] == "Internal server error"
    assert "sensitive" not in response.text


def test_openapi_and_swagger_docs_are_available():
    with TestClient(app) as client:
        docs = client.get("/docs")
        schema = client.get("/openapi.json")
    assert docs.status_code == 200
    assert schema.status_code == 200
    paths = schema.json()["paths"]
    assert {"get"} <= set(paths["/health"])
    assert {"post"} <= set(paths["/forecast"])
    assert {"post"} <= set(paths["/inventory/optimize"])
    forecast_fields = set(schema.json()["components"]["schemas"]["ForecastRequest"]["properties"])
    assert forecast_fields == set(FEATURES) | {"forecast_date", "prediction_cutoff"}
