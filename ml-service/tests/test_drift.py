import pandas as pd
from datetime import datetime, timezone

from src.monitoring.drift import compare_feature_drift, decide_retraining


def test_numeric_and_categorical_drift_are_explainable():
    reference = pd.DataFrame({"sales_lag_1": [0., 1., 2., 3.], "family": ["A", "A", "B", "B"]})
    current = pd.DataFrame({"sales_lag_1": [10., 11., 12., 13.], "family": ["A", "A", "A", "A"]})
    drift = compare_feature_drift(reference, current)
    assert drift["sales_lag_1"]["mean_shift_sd"] > 1
    assert drift["family"]["total_variation"] == .5


def test_retraining_flag_requires_sample_and_is_only_a_flag():
    reference = pd.DataFrame({"x": range(120)})
    current = pd.DataFrame({"x": range(1000, 1120)})
    assert decide_retraining(reference=reference.iloc[:10], current=current.iloc[:10]).status == "INSUFFICIENT_DATA"
    result = decide_retraining(reference=reference, current=current)
    assert result.status == "RETRAIN_REQUIRED"
    assert result.reasons


def test_error_degradation_needs_minimum_actual_count():
    a = pd.DataFrame({"x": range(120)})
    b = a.copy()
    cfg = {"minimum_sample_size": 100, "minimum_actual_count_for_error_flag": 50,
           "mae_degradation_fraction_threshold": .2}
    assert decide_retraining(reference=a, current=b, error_degradation=.5,
                             actual_count=2, config=cfg).status == "MONITOR"
    assert decide_retraining(reference=a, current=b, error_degradation=.5,
                             actual_count=100, config=cfg).status == "RETRAIN_REQUIRED"


def test_retraining_cooldown_suppresses_repeat_flag():
    a = pd.DataFrame({"x": range(120)})
    b = pd.DataFrame({"x": range(1000, 1120)})
    cfg = {"minimum_sample_size": 100, "numeric_mean_shift_sd_threshold": 1,
           "retraining_cooldown_days": 90}
    result = decide_retraining(reference=a, current=b, config=cfg,
                               last_retraining_at=datetime(2025, 1, 1, tzinfo=timezone.utc),
                               now=datetime(2025, 2, 1, tzinfo=timezone.utc))
    assert result.status == "MONITOR"


def test_constant_reference_shift_is_finite_and_detected():
    reference = pd.DataFrame({"x": [0.] * 120})
    current = pd.DataFrame({"x": [1.] * 120})
    result = decide_retraining(reference=reference, current=current)
    assert result.status == "RETRAIN_REQUIRED"
    assert result.metrics["x"]["mean_shift_sd"] < float("inf")


def test_expected_calendar_shift_is_reported_but_not_flagged():
    reference = pd.DataFrame({"month": [1] * 120, "sales_lag_1": [4.] * 120})
    current = pd.DataFrame({"month": [7] * 120, "sales_lag_1": [4.] * 120})
    result = decide_retraining(reference=reference, current=current,
                               config={"minimum_sample_size": 100,
                                       "excluded_drift_features": ["month"]})
    assert result.status == "MONITOR"
    assert result.metrics["month"]["mean_shift_sd"] > 0
