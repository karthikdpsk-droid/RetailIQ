"""Lightweight append-only experiment tracking in JSON Lines."""
from __future__ import annotations

import json
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_EXPERIMENT_PATH = Path(__file__).resolve().parents[2] / "logs" / "experiments" / "experiments.jsonl"
_LOCK = threading.Lock()


def record_experiment(*, experiment_name: str, model_type: str, dataset: str,
                      feature_version: str, train_period: str, validation_period: str,
                      parameters: dict[str, Any], metrics: dict[str, float | None],
                      model_version: str, status: str, notes: str = "",
                      path: str | Path | None = None) -> dict[str, Any]:
    required_metrics = {"MAE", "RMSE", "R2"}
    if not required_metrics.issubset(metrics):
        raise ValueError("metrics must include MAE, RMSE, and R2")
    item = {"experiment_id": str(uuid.uuid4()), "timestamp": datetime.now(timezone.utc).isoformat(),
            "experiment_name": experiment_name, "model_type": model_type, "dataset": dataset,
            "feature_version": feature_version, "train_period": train_period,
            "validation_period": validation_period, "parameters": parameters,
            "MAE": metrics["MAE"], "RMSE": metrics["RMSE"], "R2": metrics["R2"],
            "model_version": model_version, "status": status, "notes": notes}
    output = Path(path or DEFAULT_EXPERIMENT_PATH)
    output.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(item, allow_nan=False, separators=(",", ":"))
    with _LOCK, output.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(line + "\n")
    return item


def load_experiments(path: str | Path = DEFAULT_EXPERIMENT_PATH) -> list[dict[str, Any]]:
    source = Path(path)
    if not source.exists():
        return []
    with source.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]
