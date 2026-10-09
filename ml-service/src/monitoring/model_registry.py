"""Append-only artifact registry events; artifacts themselves are immutable."""
from __future__ import annotations

import json
import csv
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_REGISTRY_PATH = Path(__file__).resolve().parents[2] / "logs" / "mlops" / "model_registry.jsonl"
DEFAULT_VALIDATION_METRICS_PATH = (
    Path(__file__).resolve().parents[2] / "configs" / "model_validation_metrics.csv"
)
_LOCK = threading.Lock()
VALID_STATUSES = {"candidate", "staging", "champion", "archived"}


def load_validation_metrics(model_version: str, comparison_path: str | Path | None = None):
    """Read actually measured historical validation metrics for the existing v1.0.2 artifact."""
    source = Path(comparison_path) if comparison_path else DEFAULT_VALIDATION_METRICS_PATH
    if model_version != "1.0.2" or not source.exists():
        return None
    with source.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            if row.get("model") == "Random Forest (tuned)":
                try:
                    return {key: float(row[key]) for key in ("MAE", "RMSE", "R2")}
                except (KeyError, TypeError, ValueError):
                    return None
    return None


def record_model_event(*, model_version: str, artifact_path: str, model_type: str,
                       feature_version: str, training_date: str | None,
                       validation_metrics: dict[str, float | None] | None,
                       status: str, previous_champion: str | None = None,
                       promotion_decision: str | None = None,
                       comparison_basis: str | None = None,
                       path: str | Path | None = None) -> dict[str, Any]:
    if status not in VALID_STATUSES:
        raise ValueError(f"status must be one of {sorted(VALID_STATUSES)}")
    record = {"event_time": datetime.now(timezone.utc).isoformat(), "model_version": model_version,
              "artifact_path": artifact_path, "model_type": model_type, "feature_version": feature_version,
              "training_date": training_date, "validation_metrics": validation_metrics,
              "status": status, "previous_champion": previous_champion,
              "promotion_decision": promotion_decision, "comparison_basis": comparison_basis}
    destination = Path(path or DEFAULT_REGISTRY_PATH)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with _LOCK, destination.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(record, allow_nan=False, separators=(",", ":")) + "\n")
    return record


def latest_model_statuses(path: str | Path = DEFAULT_REGISTRY_PATH) -> dict[str, dict[str, Any]]:
    source = Path(path)
    if not source.exists():
        return {}
    statuses = {}
    with source.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                row = json.loads(line)
                statuses[row["model_version"]] = row
    return statuses
