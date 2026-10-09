"""Idempotently seed the lifecycle ledger with the artifact currently served."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import joblib
from src.monitoring.model_registry import (DEFAULT_REGISTRY_PATH,
                                           latest_model_statuses,
                                           load_validation_metrics,
                                           record_model_event)


def main():
    artifact_path = ROOT / "models" / "demand_forecast_v1.0.2.joblib"
    artifact = joblib.load(artifact_path)
    version = str(artifact["version"])
    if version in latest_model_statuses():
        print(f"Registry already contains {version}; no event added.")
        return
    event = record_model_event(
        model_version=version, artifact_path=str(artifact_path),
        model_type=str(artifact.get("model", "Random Forest")),
        feature_version=str(artifact.get("feature_version", "1")),
        training_date=artifact.get("training_timestamp", artifact.get("train_end")),
        validation_metrics=(artifact.get("validation_metrics") or load_validation_metrics(
            version)),
        status="champion", promotion_decision="currently served artifact",
    )
    print(f"Registered served champion {event['model_version']}.")


if __name__ == "__main__":
    main()
