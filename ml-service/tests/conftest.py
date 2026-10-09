import pytest


@pytest.fixture(autouse=True)
def isolate_prediction_log(tmp_path, monkeypatch):
    """Never write test traffic into the local operational prediction log."""
    monkeypatch.setenv("RETAILIQ_PREDICTION_LOG", str(tmp_path / "predictions.jsonl"))
