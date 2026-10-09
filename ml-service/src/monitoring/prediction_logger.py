"""Append-only, privacy-minimal JSONL prediction event log."""
from __future__ import annotations

import json
import os
import threading
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_LOG_PATH = Path(__file__).resolve().parents[2] / "logs" / "monitoring" / "predictions.jsonl"
_LOCK = threading.Lock()


def log_prediction(*, endpoint: str, store_nbr: int, family: str, prediction: float | None,
                   model_version: str, request_id: str | None, status: str,
                   forecast_date: date | str | None = None, actual_value: float | None = None,
                   path: str | Path | None = None, error_category: str | None = None) -> dict[str, Any]:
    """Persist one outcome. Request features, credentials, and exception text are never logged."""
    if status not in {"success", "failure"}:
        raise ValueError("status must be success or failure")
    if status == "success" and prediction is None:
        raise ValueError("successful prediction requires a value")
    target_date = date.fromisoformat(forecast_date) if isinstance(forecast_date, str) else forecast_date
    record: dict[str, Any] = {
        "prediction_id": str(uuid.uuid4()),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "endpoint": endpoint,
        "store_nbr": int(store_nbr),
        "family": str(family),
        "forecast_date": target_date.isoformat() if target_date else None,
        "prediction": float(prediction) if prediction is not None else None,
        "model_version": str(model_version),
        "request_id": request_id,
        "status": status,
        "actual_value": actual_value,
    }
    if error_category:
        record["error_category"] = error_category
    destination = Path(path or os.getenv("RETAILIQ_PREDICTION_LOG", DEFAULT_LOG_PATH))
    destination.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    with _LOCK:
        with destination.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(line + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    return record


def load_prediction_records(path: str | Path | None = None) -> list[dict[str, Any]]:
    source = Path(path or os.getenv("RETAILIQ_PREDICTION_LOG", DEFAULT_LOG_PATH))
    if not source.exists():
        return []
    records = []
    seen_ids: set[str] = set()
    with source.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Malformed prediction log line {line_number}") from exc
            if not isinstance(row, dict) or not row.get("prediction_id"):
                raise ValueError(f"Invalid prediction record on line {line_number}")
            if row["prediction_id"] in seen_ids:
                raise ValueError(f"Duplicate prediction_id on line {line_number}")
            seen_ids.add(row["prediction_id"])
            records.append(row)
    return records
