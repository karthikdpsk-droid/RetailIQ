"""Chronological champion/challenger replay that never selects on final test."""
from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
import yaml
from sklearn.ensemble import RandomForestRegressor
from sklearn.pipeline import Pipeline

from src.forecasting import (CATEGORICAL, FEATURES, MAX_FIT_ROWS, TARGET,
                             chronological_split, load_data, make_preprocessor, metrics)
from src.monitoring.experiments import record_experiment
from src.monitoring.model_registry import (latest_model_statuses, load_validation_metrics,
                                           record_model_event)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
CURRENT_MODEL = PROJECT_ROOT / "models" / "demand_forecast_v1.0.2.joblib"
DEFAULT_DATASET = PROJECT_ROOT / "data" / "processed" / "retailiq_leakage_safe_corrected.csv"
DEFAULT_CONFIG = PROJECT_ROOT / "configs" / "retraining.yaml"


def next_model_version(version: str) -> str:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        raise ValueError(f"Unsupported model version: {version}")
    major, minor, patch = map(int, match.groups())
    return f"{major}.{minor}.{patch + 1}"


def challenger_passes(champion: dict[str, float], candidate: dict[str, float], *,
                      minimum_mae_improvement: float = 0.01) -> bool:
    """Require >=1% paired MAE improvement, no RMSE regression, and no R2 regression."""
    if minimum_mae_improvement < 0 or minimum_mae_improvement >= 1:
        raise ValueError("minimum_mae_improvement must be in [0, 1)")
    keys = ("MAE", "RMSE", "R2")
    if any(key not in row or not np.isfinite(float(row[key])) for row in (champion, candidate) for key in keys):
        return False
    return (candidate["MAE"] <= champion["MAE"] * (1 - minimum_mae_improvement)
            and candidate["RMSE"] <= champion["RMSE"]
            and candidate["R2"] >= champion["R2"])


def _build_model(parameters: dict[str, Any]) -> Pipeline:
    allowed = {"n_estimators", "max_depth", "min_samples_leaf", "random_state", "n_jobs"}
    if set(parameters) - allowed:
        raise ValueError("Unsupported Random Forest parameter")
    estimator = RandomForestRegressor(**parameters)
    return Pipeline([("preprocess", make_preprocessor()), ("model", estimator)])


def run_retraining(*, dataset_path: str | Path = DEFAULT_DATASET,
                   champion_path: str | Path = CURRENT_MODEL,
                   config_path: str | Path = DEFAULT_CONFIG,
                   model_dir: str | Path | None = None,
                   registry_path: str | Path | None = None,
                   experiment_path: str | Path | None = None,
                   challenger_parameters: dict[str, Any] | None = None,
                   promote: bool = False,
                   minimum_mae_improvement: float | None = None) -> dict[str, Any]:
    """Train a new version; promotion requires explicit opt-in and the paired gate.

    The 2017+ test window is never passed to fit or predict. Comparison refits both
    parameter sets on identical training rows because the deployed v1.0.2 artifact
    was trained through the historical validation window; scoring that artifact on
    that window would be contaminated. This paired refit is a proxy, not its score.
    """
    dataset_path, champion_path = Path(dataset_path), Path(champion_path)
    data = load_data(dataset_path)
    train, validation, _test = chronological_split(data)
    common = validation[validation["sales_lag_1"].notna() & validation["sales_lag_7"].notna()].copy()
    if common.empty:
        raise ValueError("No comparable validation rows with required baseline lags")
    fit = train.sample(n=min(MAX_FIT_ROWS, len(train)), random_state=42).sort_index()
    metadata = joblib.load(champion_path)
    champion_version = str(metadata["version"])
    champion_validation = metadata.get("validation_metrics") or load_validation_metrics(champion_version)
    config = yaml.safe_load(Path(config_path).read_text(encoding="utf-8"))
    champion_parameters = config["champion_parameters"]
    challenger_parameters = challenger_parameters or config["candidate_parameters"]
    if minimum_mae_improvement is None:
        minimum_mae_improvement = float(config["acceptance"]["minimum_mae_improvement_fraction"])
    champion_pipe, candidate_pipe = _build_model(champion_parameters), _build_model(challenger_parameters)
    champion_pipe.fit(fit[FEATURES], fit[TARGET])
    candidate_pipe.fit(fit[FEATURES], fit[TARGET])
    y = common[TARGET].to_numpy()
    champion_metrics = metrics(y, np.maximum(0, champion_pipe.predict(common[FEATURES])))
    candidate_metrics = metrics(y, np.maximum(0, candidate_pipe.predict(common[FEATURES])))
    passes = challenger_passes(champion_metrics, candidate_metrics,
                               minimum_mae_improvement=minimum_mae_improvement)
    # Mirror the established final-fit policy: after the selection score is fixed,
    # refit the accepted parameters on train + validation. The test rows remain unused.
    development = pd.concat([train, validation], ignore_index=True)
    final_fit = development.sample(n=min(MAX_FIT_ROWS, len(development)), random_state=42).sort_index()
    candidate_artifact_pipeline = _build_model(challenger_parameters)
    candidate_artifact_pipeline.fit(final_fit[FEATURES], final_fit[TARGET])
    candidate_version = next_model_version(champion_version)
    target_dir = Path(model_dir or PROJECT_ROOT / "models")
    target_dir.mkdir(parents=True, exist_ok=True)
    artifact_path = target_dir / f"demand_forecast_v{candidate_version}.joblib"
    try:
        reservation = artifact_path.open("xb")
    except FileExistsError as exc:
        raise FileExistsError(f"Refusing to overwrite {artifact_path}") from exc
    reservation.close()
    training_time = datetime.now(timezone.utc).isoformat()
    promoted = bool(promote and passes)
    registry_status = "staging" if promoted else "candidate"
    decision = ("promotion criteria passed; explicitly staged; serving artifact unchanged" if promoted else
                "candidate retained; explicit promotion disabled" if passes else
                "candidate retained; paired validation acceptance criteria failed")
    artifact = {"pipeline": candidate_artifact_pipeline, "features": list(FEATURES), "categorical": list(CATEGORICAL),
                "version": candidate_version, "model": "Random Forest",
                "hyperparameters": challenger_parameters, "feature_version": "1",
                "train_end": str(development.date.max().date()), "training_timestamp": training_time,
                "training_rows": len(final_fit), "evaluation_training_rows": len(fit),
                "validation_rows": len(common),
                "training_period": f"{development.date.min().date()}..{development.date.max().date()}",
                "evaluation_training_period": f"{train.date.min().date()}..{train.date.max().date()}",
                "dataset_path": str(dataset_path), "validation_metrics": candidate_metrics,
                "status": registry_status}
    temporary_path = artifact_path.with_name(artifact_path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        joblib.dump(artifact, temporary_path)
        os.replace(temporary_path, artifact_path)
    except Exception:
        if temporary_path.exists():
            temporary_path.unlink()
        artifact_path.unlink(missing_ok=True)
        raise
    registry_file = Path(registry_path) if registry_path else PROJECT_ROOT / "logs" / "mlops" / "model_registry.jsonl"
    known_statuses = latest_model_statuses(registry_file)
    if champion_version not in known_statuses:
        record_model_event(model_version=champion_version, artifact_path=str(champion_path),
                           model_type=str(metadata.get("model", "Random Forest")),
                           feature_version=str(metadata.get("feature_version", "1")),
                           training_date=metadata.get("training_timestamp", metadata.get("train_end")),
                           validation_metrics=champion_validation, status="champion",
                           promotion_decision="existing serving artifact; retained as champion",
                           path=registry_file)
    record_model_event(model_version=candidate_version, artifact_path=str(artifact_path),
                       model_type="Random Forest", feature_version="1", training_date=training_time,
                       validation_metrics=candidate_metrics, status=registry_status,
                       previous_champion=champion_version, promotion_decision=decision,
                       comparison_basis="paired chronological refit proxy; not serialized champion score",
                       path=registry_file)
    record_experiment(experiment_name="batch_retraining_challenger", model_type="Random Forest",
                      dataset=str(dataset_path), feature_version="1",
                      train_period=f"{train.date.min().date()}..{train.date.max().date()}",
                      validation_period=f"{common.date.min().date()}..{common.date.max().date()}",
                      parameters=challenger_parameters, metrics=candidate_metrics,
                      model_version=candidate_version, status=registry_status,
                      notes=decision, path=experiment_path)
    return {"model_version": candidate_version, "artifact_path": str(artifact_path),
            "training_rows": len(final_fit), "evaluation_training_rows": len(fit),
            "validation_rows": len(common),
            "champion_proxy_metrics": champion_metrics, "candidate_metrics": candidate_metrics,
            "acceptance_passed": passes, "promotion_requested": promote, "promoted": promoted,
            "decision": decision, "test_rows_used_for_selection": 0,
            "comparison_basis": "paired chronological refit proxy; not serialized champion score"}


if __name__ == "__main__":
    print(json.dumps(run_retraining(), indent=2))
