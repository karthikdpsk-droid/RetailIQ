from src.monitoring.experiments import load_experiments, record_experiment
from src.monitoring.model_registry import latest_model_statuses, record_model_event


def test_experiment_event_is_reproducible_and_loadable(tmp_path):
    path = tmp_path / "experiments.jsonl"
    item = record_experiment(experiment_name="small-test", model_type="RandomForest",
                             dataset="fixture.csv", feature_version="1", train_period="a..b",
                             validation_period="c..d", parameters={"seed": 42},
                             metrics={"MAE": 1., "RMSE": 2., "R2": .9}, model_version="1.0.3",
                             status="candidate", path=path)
    assert load_experiments(path)[0] == item
    assert item["parameters"]["seed"] == 42


def test_model_registry_keeps_latest_status_without_overwriting_artifacts(tmp_path):
    path = tmp_path / "registry.jsonl"
    record_model_event(model_version="1.0.3", artifact_path="candidate.joblib", model_type="RF",
                       feature_version="1", training_date="2025-01-01", validation_metrics={"MAE": 1.},
                       status="candidate", path=path)
    record_model_event(model_version="1.0.3", artifact_path="candidate.joblib", model_type="RF",
                       feature_version="1", training_date="2025-01-01", validation_metrics={"MAE": 1.},
                       status="staging", path=path)
    assert latest_model_statuses(path)["1.0.3"]["status"] == "staging"
