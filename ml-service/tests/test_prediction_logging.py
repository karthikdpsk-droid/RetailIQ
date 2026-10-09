import json

from src.monitoring.prediction_logger import load_prediction_records, log_prediction


def test_prediction_log_is_minimal_and_records_null_actual(tmp_path):
    path = tmp_path / "predictions.jsonl"
    record = log_prediction(endpoint="/forecast", store_nbr=3, family="FOOD",
                            forecast_date="2025-01-02", prediction=12.5,
                            model_version="demand_forecast_v1.0.2", request_id="req-1",
                            status="success", path=path)
    assert record["actual_value"] is None
    assert len(load_prediction_records(path)) == 1
    raw = path.read_text()
    assert "password" not in raw and "sales_lag" not in raw
    assert json.loads(raw)["status"] == "success"


def test_prediction_log_marks_failure_without_inventing_prediction(tmp_path):
    row = log_prediction(endpoint="/forecast", store_nbr=3, family="FOOD",
                         prediction=None, model_version="v1", request_id="r",
                         status="failure", error_category="RuntimeError",
                         path=tmp_path / "events.jsonl")
    assert row["prediction"] is None and row["actual_value"] is None
    assert row["error_category"] == "RuntimeError"


def test_prediction_log_rejects_invalid_status_and_success_without_value(tmp_path):
    import pytest
    with pytest.raises(ValueError):
        log_prediction(endpoint="/forecast", store_nbr=1, family="F", prediction=None,
                       model_version="v1", request_id=None, status="success", path=tmp_path / "x")


def test_prediction_reader_detects_duplicate_event_ids(tmp_path):
    import pytest
    path = tmp_path / "duplicate.jsonl"
    record = log_prediction(endpoint="/forecast", store_nbr=1, family="F", prediction=2.,
                            model_version="v1", request_id=None, status="success", path=path)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record) + "\n")
    with pytest.raises(ValueError, match="Duplicate prediction_id"):
        load_prediction_records(path)
