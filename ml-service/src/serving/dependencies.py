"""Central, thread-safe one-time loading of the finalized model artifact."""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import joblib
from fastapi import Depends, Request

from src.forecasting import FEATURES
from src.monitoring.model_registry import (
    DEFAULT_VALIDATION_METRICS_PATH,
    load_validation_metrics,
)

logger = logging.getLogger(__name__)
PROJECT_ROOT = Path(__file__).resolve().parents[2]
MODEL_ARTIFACT_PATH = PROJECT_ROOT / "models" / "demand_forecast_v1.0.2.joblib"


class ModelUnavailableError(RuntimeError):
    """Raised when the configured serialized forecasting model cannot load."""


@dataclass(frozen=True)
class ModelBundle:
    pipeline: Any
    version: str
    features: tuple[str, ...]
    artifact_path: str | None = None
    model_type: str | None = None
    feature_version: str | None = None
    training_date: str | None = None
    validation_metrics: dict[str, Any] | None = None
    status: str = "champion"

    @property
    def api_version(self) -> str:
        return f"demand_forecast_v{self.version}"


class ModelRegistry:
    """Loads once per process and caches either the model or the load failure."""

    def __init__(self, path: Path | str = MODEL_ARTIFACT_PATH, loader: Callable = joblib.load):
        self._path = Path(path)
        self._loader = loader
        self._lock = threading.Lock()
        self._attempted = False
        self._bundle: ModelBundle | None = None
        self._error: ModelUnavailableError | None = None

    def get(self) -> ModelBundle:
        with self._lock:
            if not self._attempted:
                self._attempted = True
                try:
                    metadata = self._loader(self._path)
                    version = str(metadata["version"])
                    features = tuple(metadata["features"])
                    pipeline = metadata["pipeline"]
                    if not version or features != tuple(FEATURES) or not callable(getattr(pipeline, "predict", None)):
                        raise ValueError("model metadata or pipeline contract does not match")
                    self._bundle = ModelBundle(
                        pipeline=pipeline, version=version, features=features,
                        artifact_path=str(self._path), model_type=metadata.get("model", "Random Forest"),
                        feature_version=str(metadata.get("feature_version", "1")),
                        training_date=metadata.get("training_timestamp", metadata.get("train_end")),
                        validation_metrics=(metadata.get("validation_metrics") or
                                            load_validation_metrics(version, DEFAULT_VALIDATION_METRICS_PATH)),
                        status=metadata.get("status", "champion"),
                    )
                    logger.info("forecast_model_loaded", extra={"model_version": self._bundle.api_version})
                except Exception as exc:
                    self._error = ModelUnavailableError("Forecast model is unavailable")
                    logger.error("forecast_model_load_failed", extra={"error_category": type(exc).__name__})
            if self._error:
                raise self._error
            if self._bundle is None:
                raise ModelUnavailableError("Forecast model is unavailable")
            return self._bundle

    def describe(self) -> dict[str, Any]:
        """Expose the serving registry entry without changing endpoint contracts."""
        bundle = self.get()
        return {"model_version": bundle.version, "artifact_path": bundle.artifact_path,
                "model_type": bundle.model_type, "feature_version": bundle.feature_version,
                "training_date": bundle.training_date,
                "validation_metrics": bundle.validation_metrics, "status": bundle.status}


def get_model_registry(request: Request) -> ModelRegistry:
    return request.app.state.model_registry


def get_model_bundle(registry: ModelRegistry = Depends(get_model_registry)) -> ModelBundle:
    return registry.get()
