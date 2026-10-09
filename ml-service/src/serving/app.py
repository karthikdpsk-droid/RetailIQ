"""FastAPI application for RetailIQ forecasting and inventory recommendations."""
from __future__ import annotations

import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .dependencies import (
    ModelRegistry, ModelUnavailableError, get_model_bundle, get_model_registry,
)
from .schemas import (
    ErrorResponse, ForecastRequest, ForecastResponse, HealthResponse,
    InventoryOptimizeRequest, InventoryOptimizeResponse,
)
from .services import InferenceFailure, forecast, optimize_inventory

logger = logging.getLogger("retailiq.serving")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def _request_id(request: Request) -> str:
    return getattr(request.state, "request_id", None) or str(uuid.uuid4())


def _error(request: Request, message: str, status_code: int, details=None) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={
        "status": "error", "message": message,
        "request_id": _request_id(request), "details": details,
    })


def create_app(model_registry: ModelRegistry | None = None) -> FastAPI:
    registry = model_registry or ModelRegistry()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        try:
            registry.get()
        except ModelUnavailableError:
            # Keep the process alive so /health can report degraded status.
            logger.warning("serving_started_without_model", extra={"error_category": "model_unavailable"})
        yield

    application = FastAPI(
        title="RetailIQ ML Service",
        description="One-day demand forecasts and assumption-explicit inventory recommendations.",
        version="1.0.0",
        lifespan=lifespan,
    )
    application.state.model_registry = registry

    allowed_origins = [origin.strip() for origin in os.getenv("RETAILIQ_CORS_ORIGINS", "").split(",") if origin.strip()]
    if "*" in allowed_origins:
        raise RuntimeError("RETAILIQ_CORS_ORIGINS must list explicit origins; wildcard CORS is disabled")
    if allowed_origins:
        application.add_middleware(
            CORSMiddleware, allow_origins=allowed_origins,
            allow_credentials=False, allow_methods=["GET", "POST"],
            allow_headers=["Content-Type", "X-Request-ID"], expose_headers=["X-Request-ID"],
        )

    @application.middleware("http")
    async def trace_requests(request: Request, call_next):
        supplied = request.headers.get("X-Request-ID", "")
        request_id = supplied if REQUEST_ID_PATTERN.fullmatch(supplied) else str(uuid.uuid4())
        request.state.request_id = request_id
        started = time.perf_counter()
        logger.info("request_received", extra={
            "request_id": request_id, "endpoint": request.url.path,
            "http_method": request.method,
        })
        try:
            response = await call_next(request)
        except Exception:
            logger.exception("request_middleware_failure", extra={
                "request_id": request_id, "endpoint": request.url.path,
                "error_category": "unhandled_middleware_exception",
            })
            response = _error(request, "Internal server error", 500)
        response.headers["X-Request-ID"] = request_id
        logger.info("request_completed", extra={
            "request_id": request_id, "endpoint": request.url.path,
            "status_code": response.status_code,
            "duration_ms": round((time.perf_counter() - started) * 1000, 3),
        })
        return response

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        details = [{"location": ".".join(str(part) for part in err["loc"]), "message": err["msg"]}
                   for err in exc.errors()]
        logger.info("request_validation_failed", extra={"request_id": _request_id(request), "error_category": "validation"})
        return _error(request, "Invalid request", 422, details)

    @application.exception_handler(ModelUnavailableError)
    async def model_error_handler(request: Request, _exc: ModelUnavailableError):
        return _error(request, "Forecast model is unavailable", 503)

    @application.exception_handler(InferenceFailure)
    async def inference_error_handler(request: Request, _exc: InferenceFailure):
        return _error(request, "Forecast inference failed", 500)

    @application.exception_handler(HTTPException)
    async def http_error_handler(request: Request, exc: HTTPException):
        messages = {404: "Resource not found", 405: "Method not allowed"}
        message = messages.get(exc.status_code, "Request could not be completed")
        return _error(request, message, exc.status_code)

    @application.exception_handler(Exception)
    async def unexpected_error_handler(request: Request, exc: Exception):
        logger.exception("request_failed", extra={
            "request_id": _request_id(request), "endpoint": request.url.path,
            "error_category": type(exc).__name__,
        })
        return _error(request, "Internal server error", 500)

    @application.get(
        "/health", response_model=HealthResponse, tags=["Operations"],
        summary="Check service and model readiness",
        responses={503: {"model": HealthResponse, "description": "Service is running without a usable model"}},
    )
    def health(reg: ModelRegistry = Depends(get_model_registry)):
        try:
            bundle = reg.get()
        except ModelUnavailableError:
            return JSONResponse(status_code=503, content={
                "status": "degraded", "service": "retailiq-ml-service", "model_version": None,
            })
        return HealthResponse(status="ok", model_version=bundle.api_version)

    @application.post(
        "/forecast", response_model=ForecastResponse, tags=["Forecasting"],
        summary="Create a one-day-ahead demand forecast",
        description="Accepts the serialized model's exact 25 feature columns plus a target forecast date. Returns a nonnegative point forecast; prediction intervals are not calibrated.",
        responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
    )
    def create_forecast(payload: ForecastRequest, request: Request,
                        bundle=Depends(get_model_bundle)):
        logger.info("forecast_request_received", extra={
            "request_id": _request_id(request), "store_nbr": payload.store_nbr,
            "family": payload.family, "model_version": bundle.api_version,
        })
        return forecast(bundle, payload, _request_id(request))

    @application.post(
        "/inventory/optimize", response_model=InventoryOptimizeResponse,
        tags=["Inventory"], summary="Calculate an inventory recommendation",
        description="Uses the existing inventory policy. On-hand stock is optional; without it, status is INVENTORY_DATA_REQUIRED and order quantity remains unavailable. All inventory values must use sales-equivalent units.",
        responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
    )
    def inventory_optimize(payload: InventoryOptimizeRequest, request: Request,
                           bundle=Depends(get_model_bundle)):
        try:
            result = optimize_inventory(bundle, payload)
        except (ValueError, OverflowError) as exc:
            logger.info("inventory_input_rejected", extra={
                "request_id": _request_id(request), "store_nbr": payload.store_nbr,
                "family": payload.family, "error_category": type(exc).__name__,
            })
            return _error(request, "Invalid inventory calculation input", 422)
        logger.info("inventory_recommendation_succeeded", extra={
            "request_id": _request_id(request), "store_nbr": payload.store_nbr,
            "family": payload.family, "model_version": bundle.api_version,
        })
        return result

    return application


app = create_app()
