from datetime import datetime, timezone
from math import isclose
from statistics import NormalDist

import pytest

from src.inventory.optimization import (
    estimate_demand_variability, load_config, recommend_from_forecasts,
    recommend_inventory, stock_parameters,
)


def history(values=(10, 20, 30, 40)):
    return list(values)


def item(**overrides):
    result = {"store_nbr": 1, "family": "GROCERY", "forecast_demand": 10,
              "historical_demand": history(), "lead_time_days": 7,
              "prediction_cutoff": "2025-01-31", "model_version": "1.0.2"}
    result.update(overrides)
    return result


def test_variability_sample_std_zero_intermit_low_and_missing():
    assert estimate_demand_variability([0, 0, 0]) == 0
    assert estimate_demand_variability([0, 0, 10, 0]) > 0
    assert estimate_demand_variability([0, 1]) > 0
    assert estimate_demand_variability([5]) is None
    assert estimate_demand_variability(None) is None


def test_variability_filters_future_and_requires_cutoff_for_dated_values():
    dated = [{"date": "2025-01-29", "sales": 1}, {"date": "2025-01-30", "sales": 2},
             {"date": "2025-02-01", "sales": 1000}]
    assert estimate_demand_variability(dated, cutoff_date="2025-01-31") == pytest.approx(0.5 ** 0.5)
    with pytest.raises(ValueError, match="cutoff_date"):
        estimate_demand_variability(dated)


def test_safety_reorder_and_target_formulas_and_zero_lead():
    params = stock_parameters(10, 4, 9, 0.95, 2)
    z = NormalDist().inv_cdf(0.95)
    assert params["safety_stock"] == pytest.approx(z * 4 * 3)
    assert params["reorder_point"] == pytest.approx(90 + z * 12)
    assert params["target_stock"] == pytest.approx(110 + z * 4 * (11 ** 0.5))
    zero_lead = stock_parameters(10, 4, 0, 0.95, 1)
    assert zero_lead["safety_stock"] == 0
    assert zero_lead["reorder_point"] == 0
    assert zero_lead["target_stock"] > 0


def test_sensitivity_is_monotonic_for_service_lead_and_variability():
    baseline = stock_parameters(50, 8, 7, 0.95, 1)
    assert stock_parameters(50, 8, 7, 0.99, 1)["safety_stock"] > baseline["safety_stock"]
    assert stock_parameters(50, 8, 14, 0.95, 1)["reorder_point"] > baseline["reorder_point"]
    assert stock_parameters(50, 12, 7, 0.95, 1)["target_stock"] > baseline["target_stock"]


def test_mode_a_never_fabricates_inventory_or_order():
    result = recommend_inventory(item())
    assert result["forecast_demand"] == 10
    assert result["demand_variability"] is not None
    assert result["safety_stock"] is not None
    assert result["reorder_point"] is not None
    assert result["target_stock"] is not None
    assert result["current_inventory"] is None
    assert result["inventory_position"] is None
    assert result["recommended_order_quantity"] is None
    assert result["stock_status"] == "UNKNOWN / INVENTORY_DATA_REQUIRED"
    assert result["inventory_position_basis"] is None
    assert result["model_version"] == "1.0.2"


def test_inventory_position_backorders_and_order_quantity():
    result = recommend_inventory(item(current_inventory=20, on_order_inventory=15, backorder_quantity=5))
    assert result["inventory_position"] == 30
    assert result["inventory_position_basis"] == "on_hand + (on_order or 0) - (backorders or 0)"
    assert result["recommended_order_quantity"] == pytest.approx(max(0, result["target_stock"] - 30))
    assert result["stock_status"] in {"CRITICAL", "REORDER", "ADEQUATE", "ABOVE_TARGET"}


def test_zero_stock_reorder_and_above_target_status():
    zero = recommend_inventory(item(forecast_demand=40, current_inventory=0))
    assert zero["recommended_order_quantity"] > 0
    assert zero["stock_status"] in {"CRITICAL", "REORDER"}
    above = recommend_inventory(item(forecast_demand=2, current_inventory=10000))
    assert above["stock_status"] == "ABOVE_TARGET"
    assert above["recommended_order_quantity"] == 0


def test_moq_and_maximum_stock_constraints_only_when_configured():
    base = recommend_inventory(item(current_inventory=0))
    cfg = {"minimum_order_quantity": 200}
    with_moq = recommend_inventory(item(current_inventory=0), cfg)
    assert with_moq["recommended_order_quantity"] == 200
    capped = recommend_inventory(item(forecast_demand=10, current_inventory=0),
                                 {"maximum_stock_level": 130})
    assert capped["target_stock"] == 130
    assert capped["recommended_order_quantity"] == 130
    assert base["recommended_order_quantity"] != with_moq["recommended_order_quantity"]


def test_missing_forecast_and_insufficient_history_fail_closed():
    missing_forecast = recommend_inventory(item(forecast_demand=None, current_inventory=10))
    assert missing_forecast["stock_status"] == "UNKNOWN / FORECAST_REQUIRED"
    assert missing_forecast["recommended_order_quantity"] is None
    short = recommend_inventory(item(historical_demand=[1], current_inventory=10))
    assert short["demand_variability"] is None
    assert short["stock_status"] == "UNKNOWN / INSUFFICIENT_HISTORY"
    assert short["recommended_order_quantity"] is None


@pytest.mark.parametrize("field,value", [
    ("current_inventory", -1), ("on_order_inventory", -1),
    ("backorder_quantity", -1), ("forecast_demand", float("nan")),
    ("forecast_demand", float("inf")), ("lead_time_days", -1),
    ("lead_time_days", 366), ("service_level", 1.0),
])
def test_invalid_inputs_rejected(field, value):
    with pytest.raises(ValueError):
        recommend_inventory(item(**{field: value}))


def test_huge_demand_overflow_rejected():
    with pytest.raises((ValueError, OverflowError)):
        recommend_inventory(item(forecast_demand=1e308, lead_time_days=365))


def test_constraint_conflicts_are_explicit():
    conflict = recommend_inventory(item(forecast_demand=100, current_inventory=0),
                                  {"maximum_stock_level": 1})
    assert conflict["stock_status"] == "UNKNOWN / CONSTRAINT_CONFLICT"
    assert conflict["recommended_order_quantity"] is None
    moq_conflict = recommend_inventory(item(current_inventory=0),
                                      {"minimum_order_quantity": 100, "maximum_stock_level": 50})
    assert moq_conflict["stock_status"] == "UNKNOWN / CONSTRAINT_CONFLICT"
    assert moq_conflict["recommended_order_quantity"] is None


def test_forecast_adapter_excludes_future_actuals_and_is_reproducible():
    rows = [{"store_nbr": 1, "family": "GROCERY", "forecast_date": "2025-02-01", "predicted_demand": 10}]
    history_map = {(1, "GROCERY"): [
        {"date": "2025-01-30", "sales": 10}, {"date": "2025-01-31", "sales": 20},
        {"date": "2025-02-01", "sales": 100000},
    ]}
    fixed = datetime(2025, 2, 2, tzinfo=timezone.utc)
    first = recommend_from_forecasts(rows, history_map, model_version="1.0.2")
    second = recommend_from_forecasts(rows, history_map, model_version="1.0.2")
    assert first[0]["demand_variability"] == pytest.approx(50 ** 0.5)
    assert first[0]["demand_variability"] == second[0]["demand_variability"]
    assert first[0]["stock_status"] == "UNKNOWN / INVENTORY_DATA_REQUIRED"


def test_timestamp_reproducible_and_default_lead_used():
    fixed = datetime(2025, 2, 1, tzinfo=timezone.utc)
    a = recommend_inventory(item(lead_time_days=None), calculation_time=fixed)
    b = recommend_inventory(item(lead_time_days=None), calculation_time=fixed)
    assert a == b
    assert a["lead_time"] == load_config()["default_lead_time_days"]
    assert a["calculation_timestamp"] == "2025-02-01T00:00:00+00:00"
