import pandas as pd
import pytest

from src.monitoring.error_monitor import (aggregate_errors, join_actuals, monitor_actuals,
                                          monitor_historical_replay)
from src.monitoring.prediction_logger import log_prediction


def test_exact_date_actual_matching_metrics_zero_safe_and_missing_coverage(tmp_path):
    path = tmp_path / "predictions.jsonl"
    for day, prediction in (("2099-01-01", 10), ("2099-01-02", 8), ("2099-01-03", 4)):
        log_prediction(endpoint="/forecast", store_nbr=1, family="F", forecast_date=day,
                       prediction=prediction, model_version="v1", request_id=None,
                       status="success", path=path)
    actuals = pd.DataFrame({"date": ["2099-01-01", "2099-01-02"], "store_nbr": [1, 1],
                            "family": ["F", "F"], "sales": [0, 10]})
    groups, report = monitor_actuals(path, actuals, source_label="historical_replay")
    overall = groups.iloc[0]
    assert overall["n_predictions"] == 3 and overall["n_with_actual"] == 2
    assert overall["actual_coverage"] == pytest.approx(2 / 3)
    assert overall["MAE"] == pytest.approx(6)
    assert overall["RMSE"] == pytest.approx((52 ** 0.5))
    assert overall["WAPE"] == pytest.approx(120)
    assert overall["MAPE_nonzero_actual_pct"] == pytest.approx(20)
    assert overall["n_zero_actual"] == 1
    assert report["report_type"] == "historical_replay_forecast_monitoring"


def test_actual_duplicate_key_rejected_and_zero_wape_is_null():
    pred = [{"forecast_date": "2025-01-01", "store_nbr": 1, "family": "F", "prediction": 0.0}]
    actual = pd.DataFrame({"date": ["2025-01-01"], "store_nbr": [1], "family": ["F"], "sales": [0.]})
    result = aggregate_errors(join_actuals(pred, actual)).iloc[0]
    assert result["WAPE"] is None and result["MAPE_nonzero_actual_pct"] is None
    duplicate = pd.concat([actual, actual], ignore_index=True)
    with pytest.raises(ValueError, match="unique"):
        join_actuals(pred, duplicate)


def test_join_uses_target_date_not_prediction_timestamp():
    pred = [{"timestamp": "2025-01-04T08:00:00Z", "forecast_date": "2025-01-01",
             "store_nbr": 1, "family": "F", "prediction": 5.}]
    actual = pd.DataFrame({"date": ["2025-01-04"], "store_nbr": [1], "family": ["F"], "sales": [5.]})
    joined = join_actuals(pred, actual)
    assert pd.isna(joined.actual.iloc[0])


def test_actual_observation_must_arrive_after_prediction_and_replay_is_labeled(tmp_path):
    pred = [{"timestamp": "2025-01-01T12:00:00Z", "forecast_date": "2025-01-02",
             "store_nbr": 1, "family": "F", "prediction": 5.}]
    actual = pd.DataFrame({"date": ["2025-01-02"], "observed_at": ["2025-01-01T11:00:00Z"],
                           "store_nbr": [1], "family": ["F"], "sales": [5.]})
    assert pd.isna(join_actuals(pred, actual).actual.iloc[0])
    replay = tmp_path / "replay.csv"
    pd.DataFrame({"forecast_date": ["2025-01-02"], "store_nbr": [1], "family": ["F"],
                  "predicted_demand": [5.], "actual": [4.]}).to_csv(replay, index=False)
    _, report = monitor_historical_replay(replay)
    assert report["report_type"] == "historical_holdout_replay"
    assert report["MAE"] == 1.0
