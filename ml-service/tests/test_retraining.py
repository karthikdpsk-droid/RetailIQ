from datetime import date

import joblib
import numpy as np
import pandas as pd
import pytest
from sklearn.dummy import DummyRegressor
from sklearn.pipeline import Pipeline

from src.forecasting import FEATURES
from src.monitoring.experiments import load_experiments
from src.monitoring.model_registry import latest_model_statuses
from src.retraining.pipeline import challenger_passes, next_model_version, run_retraining


def test_promotion_gate_compares_mae_rmse_r2_and_version_is_incremented():
    champion = {"MAE": 100., "RMSE": 200., "R2": .8}
    assert challenger_passes(champion, {"MAE": 98., "RMSE": 200., "R2": .8})
    assert not challenger_passes(champion, {"MAE": 99.5, "RMSE": 190., "R2": .9})
    assert not challenger_passes(champion, {"MAE": 90., "RMSE": 201., "R2": .9})
    assert not challenger_passes(champion, {"MAE": 90., "RMSE": 190., "R2": .7})
    assert next_model_version("1.0.2") == "1.0.3"
    with pytest.raises(ValueError):
        next_model_version("bad")


def _training_fixture(path):
    rows = []
    for d in pd.date_range("2016-01-01", "2017-01-04"):
        for store in (1, 2):
            row = {feature: 1 for feature in FEATURES}
            row.update({"date": d, "store_nbr": store, "family": "F", "sales": float(d.day + store),
                        "city": "Quito", "state": "Pichincha", "type": "A", "cluster": 1,
                        "dcoilwtico": 50., "onpromotion": 0., "is_holiday_event": 0,
                        "year": d.year, "month": d.month, "day": d.day,
                        "day_of_week": d.dayofweek, "week_of_year": d.isocalendar().week,
                        "quarter": d.quarter, "is_weekend": int(d.dayofweek >= 5),
                        "has_promotion": 0, "sales_lag_1": 3., "sales_lag_7": 2.,
                        "sales_lag_14": 2., "sales_lag_28": 2., "sales_rolling_mean_7": 2.,
                        "sales_rolling_mean_14": 2., "sales_rolling_mean_28": 2.,
                        "sales_rolling_std_7": 1.})
            rows.append(row)
    pd.DataFrame(rows).to_csv(path, index=False)


def test_batch_retraining_creates_new_candidate_and_preserves_champion(tmp_path):
    dataset = tmp_path / "train.csv"
    _training_fixture(dataset)
    champion = tmp_path / "champion.joblib"
    joblib.dump({"version": "1.0.2", "features": FEATURES,
                 "pipeline": Pipeline([("model", DummyRegressor())])}, champion)
    before = champion.read_bytes()
    result = run_retraining(dataset_path=dataset, champion_path=champion,
                            model_dir=tmp_path / "models", registry_path=tmp_path / "registry.jsonl",
                            experiment_path=tmp_path / "experiments.jsonl",
                            challenger_parameters={"n_estimators": 3, "max_depth": 3,
                                                   "min_samples_leaf": 1, "random_state": 42,
                                                   "n_jobs": 1})
    assert result["model_version"] == "1.0.3"
    assert result["test_rows_used_for_selection"] == 0
    assert result["promoted"] is False
    assert champion.read_bytes() == before
    metadata = joblib.load(result["artifact_path"])
    assert metadata["status"] == "candidate"
    assert metadata["training_rows"] > 0 and metadata["validation_rows"] > 0
    assert metadata["train_end"] == "2016-12-31"
    statuses = latest_model_statuses(tmp_path / "registry.jsonl")
    assert statuses["1.0.2"]["status"] == "champion"
    assert statuses["1.0.3"]["status"] == "candidate"
    experiments = load_experiments(tmp_path / "experiments.jsonl")
    assert len(experiments) == 1 and experiments[0]["status"] == "candidate"
